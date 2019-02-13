package net.corda.nodeapi.internal.persistence

import co.paralleluniverse.strands.Strand
import net.corda.core.schemas.MappedSchema
import net.corda.core.utilities.contextLogger
import rx.Observable
import rx.Subscriber
import rx.subjects.PublishSubject
import rx.subjects.UnicastSubject
import java.io.Closeable
import java.sql.Connection
import java.sql.SQLException
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import javax.persistence.AttributeConverter
import javax.sql.DataSource

/**
 * Table prefix for all tables owned by the node module.
 */
const val NODE_DATABASE_PREFIX = "node_"

// This class forms part of the node config and so any changes to it must be handled with care
data class DatabaseConfig(
        val initialiseSchema: Boolean = true,
        val serverNameTablePrefix: String = "",
        val transactionIsolationLevel: TransactionIsolationLevel = TransactionIsolationLevel.REPEATABLE_READ,
        val exportHibernateJMXStatistics: Boolean = false
)

// This class forms part of the node config and so any changes to it must be handled with care
enum class TransactionIsolationLevel {
    NONE,
    READ_UNCOMMITTED,
    READ_COMMITTED,
    REPEATABLE_READ,
    SERIALIZABLE;

    /**
     * The JDBC constant value of the same name but prefixed with TRANSACTION_ defined in [java.sql.Connection].
     */
    val jdbcValue: Int = java.sql.Connection::class.java.getField("TRANSACTION_$name").get(null) as Int
}

private val _contextDatabase = InheritableThreadLocal<CordaPersistence>()
var contextDatabase: CordaPersistence
    get() = _contextDatabase.get() ?: error("Was expecting to find CordaPersistence set on current thread: ${Strand.currentStrand()}")
    set(database) = _contextDatabase.set(database)
val contextDatabaseOrNull: CordaPersistence? get() = _contextDatabase.get()

class CordaPersistence(
        val dataSource: DataSource,
        databaseConfig: DatabaseConfig,
        schemas: Set<MappedSchema>,
        attributeConverters: Collection<AttributeConverter<*, *>> = emptySet()
) : Closeable {
    companion object {
        private val log = contextLogger()
    }

    private val defaultIsolationLevel = databaseConfig.transactionIsolationLevel
    val hibernateConfig: HibernateConfiguration by lazy {
        transaction {
            HibernateConfiguration(schemas, databaseConfig, attributeConverters)
        }
    }
    val entityManagerFactory get() = hibernateConfig.sessionFactoryForRegisteredSchemas

    data class Boundary(val txId: UUID)

    internal val transactionBoundaries = PublishSubject.create<Boundary>().toSerialized()

    init {
        // Found a unit test that was forgetting to close the database transactions.  When you close() on the top level
        // database transaction it will reset the threadLocalTx back to null, so if it isn't then there is still a
        // database transaction open.  The [transaction] helper above handles this in a finally clause for you
        // but any manual database transaction management is liable to have this problem.
        contextTransactionOrNull?.let {
            error("Was not expecting to find existing database transaction on current strand when setting database: ${Strand.currentStrand()}, $it")
        }
        _contextDatabase.set(this)
        // Check not in read-only mode.
        transaction {
            check(!connection.metaData.isReadOnly) { "Database should not be readonly." }

            checkCorrectAttachmentsContractsTableName(connection)
            checkCorrectCheckpointTypeOnPostgres(connection)
        }
    }

    fun currentOrNew(isolation: TransactionIsolationLevel = defaultIsolationLevel): DatabaseTransaction {
        return contextTransactionOrNull ?: newTransaction(isolation)
    }

    fun newTransaction(isolation: TransactionIsolationLevel = defaultIsolationLevel): DatabaseTransaction {
        return DatabaseTransaction(isolation.jdbcValue, contextTransactionOrNull, this).also {
            contextTransactionOrNull = it
        }
    }

    /**
     * Creates an instance of [DatabaseTransaction], with the given transaction isolation level.
     */
    fun createTransaction(isolationLevel: TransactionIsolationLevel = defaultIsolationLevel): DatabaseTransaction {
        // We need to set the database for the current [Thread] or [Fiber] here as some tests share threads across databases.
        _contextDatabase.set(this)
        return currentOrNew(isolationLevel)
    }

    fun createSession(): Connection {
        // We need to set the database for the current [Thread] or [Fiber] here as some tests share threads across databases.
        _contextDatabase.set(this)
        currentDBSession().flush()
        return contextTransaction.connection
    }

    /**
     * Executes given statement in the scope of transaction, with the given isolation level.
     * @param isolationLevel isolation level for the transaction.
     * @param statement to be executed in the scope of this transaction.
     */
    fun <T> transaction(isolationLevel: TransactionIsolationLevel, statement: DatabaseTransaction.() -> T): T {
        _contextDatabase.set(this)
        return transaction(isolationLevel, 2, statement)
    }

    /**
     * Executes given statement in the scope of transaction with the transaction level specified at the creation time.
     * @param statement to be executed in the scope of this transaction.
     */
    fun <T> transaction(statement: DatabaseTransaction.() -> T): T = transaction(defaultIsolationLevel, statement)

    private fun <T> transaction(isolationLevel: TransactionIsolationLevel, recoverableFailureTolerance: Int, statement: DatabaseTransaction.() -> T): T {
        val outer = contextTransactionOrNull
        return if (outer != null) {
            outer.statement()
        } else {
            inTopLevelTransaction(isolationLevel, recoverableFailureTolerance, statement)
        }
    }

    private fun <T> inTopLevelTransaction(isolationLevel: TransactionIsolationLevel, recoverableFailureTolerance: Int, statement: DatabaseTransaction.() -> T): T {
        var recoverableFailureCount = 0
        fun <T> quietly(task: () -> T) = try {
            task()
        } catch (t: Throwable) {
            log.warn("Cleanup task failed:", t)
        }
        while (true) {
            val transaction = contextDatabase.currentOrNew(isolationLevel) // XXX: Does this code really support statement changing the contextDatabase?
            try {
                val answer = transaction.statement()
                transaction.commit()
                return answer
            } catch (e: SQLException) {
                quietly(transaction::rollback)
                if (++recoverableFailureCount > recoverableFailureTolerance) throw e
                log.warn("Caught failure, will retry:", e)
            } catch (e: Throwable) {
                quietly(transaction::rollback)
                throw e
            } finally {
                quietly(transaction::close)
            }
        }
    }

    override fun close() {
        // DataSource doesn't implement AutoCloseable so we just have to hope that the implementation does so that we can close it
        (dataSource as? AutoCloseable)?.close()
    }
}

/**
 * Buffer observations until after the current database transaction has been closed.  Observations are never
 * dropped, simply delayed.
 *
 * Primarily for use by component authors to publish observations during database transactions without racing against
 * closing the database transaction.
 *
 * For examples, see the call hierarchy of this function.
 */
fun <T : Any> rx.Observer<T>.bufferUntilDatabaseCommit(): rx.Observer<T> {
    val currentTxId = contextTransaction.id
    val databaseTxBoundary: Observable<CordaPersistence.Boundary> = contextDatabase.transactionBoundaries.first { it.txId == currentTxId }
    val subject = UnicastSubject.create<T>()
    subject.delaySubscription(databaseTxBoundary).subscribe(this)
    databaseTxBoundary.doOnCompleted { subject.onCompleted() }
    return subject
}

// A subscriber that delegates to multiple others, wrapping a database transaction around the combination.
private class DatabaseTransactionWrappingSubscriber<U>(private val db: CordaPersistence?) : Subscriber<U>() {
    // Some unsubscribes happen inside onNext() so need something that supports concurrent modification.
    val delegates = CopyOnWriteArrayList<Subscriber<in U>>()

    fun forEachSubscriberWithDbTx(block: Subscriber<in U>.() -> Unit) {
        (db ?: contextDatabase).transaction {
            delegates.filter { !it.isUnsubscribed }.forEach {
                it.block()
            }
        }
    }

    override fun onCompleted() = forEachSubscriberWithDbTx { onCompleted() }

    override fun onError(e: Throwable?) = forEachSubscriberWithDbTx { onError(e) }

    override fun onNext(s: U) = forEachSubscriberWithDbTx { onNext(s) }

    override fun onStart() = forEachSubscriberWithDbTx { onStart() }

    fun cleanUp() {
        if (delegates.removeIf { it.isUnsubscribed }) {
            if (delegates.isEmpty()) {
                unsubscribe()
            }
        }
    }
}

// A subscriber that wraps another but does not pass on observations to it.
private class NoOpSubscriber<U>(t: Subscriber<in U>) : Subscriber<U>(t) {
    override fun onCompleted() {}
    override fun onError(e: Throwable?) {}
    override fun onNext(s: U) {}
}

/**
 * Wrap delivery of observations in a database transaction.  Multiple subscribers will receive the observations inside
 * the same database transaction.  This also lazily subscribes to the source [rx.Observable] to preserve any buffering
 * that might be in place.
 */
fun <T : Any> rx.Observable<T>.wrapWithDatabaseTransaction(db: CordaPersistence? = null): rx.Observable<T> {
    var wrappingSubscriber = DatabaseTransactionWrappingSubscriber<T>(db)
    // Use lift to add subscribers to a special subscriber that wraps a database transaction around observations.
    // Each subscriber will be passed to this lambda when they subscribe, at which point we add them to wrapping subscriber.
    return this.lift { toBeWrappedInDbTx: Subscriber<in T> ->
        // Add the subscriber to the wrapping subscriber, which will invoke the original subscribers together inside a database transaction.
        wrappingSubscriber.delegates.add(toBeWrappedInDbTx)
        // If we are the first subscriber, return the shared subscriber, otherwise return a subscriber that does nothing.
        if (wrappingSubscriber.delegates.size == 1) wrappingSubscriber else NoOpSubscriber(toBeWrappedInDbTx)
        // Clean up the shared list of subscribers when they unsubscribe.
    }.doOnUnsubscribe {
        wrappingSubscriber.cleanUp()
        // If cleanup removed the last subscriber reset the system, as future subscribers might need the stream again
        if (wrappingSubscriber.delegates.isEmpty()) {
            wrappingSubscriber = DatabaseTransactionWrappingSubscriber(db)
        }
    }
}

class IncompatibleAttachmentsContractsTableName(override val message: String?, override val cause: Throwable? = null) : Exception()

/** Check if any nested cause is of [SQLException] type. */
private fun Throwable.hasSQLExceptionCause(): Boolean =
        when (cause) {
            null -> false
            is SQLException -> true
            else -> cause?.hasSQLExceptionCause() ?: false
        }

class CouldNotCreateDataSourceException(override val message: String?, override val cause: Throwable? = null) : Exception()

class DatabaseIncompatibleException(override val message: String?, override val cause: Throwable? = null) : Exception()

private fun checkCorrectAttachmentsContractsTableName(connection: Connection) {
    val correctName = "NODE_ATTACHMENTS_CONTRACTS"
    val incorrectV30Name = "NODE_ATTACHMENTS_CONTRACT_CLASS_NAME"
    val incorrectV31Name = "NODE_ATTCHMENTS_CONTRACTS"

    fun warning(incorrectName: String, version: String) = "The database contains the older table name $incorrectName instead of $correctName, see upgrade notes to migrate from Corda database version $version https://docs.corda.net/head/upgrade-notes.html."

    if (!connection.metaData.getTables(null, null, correctName, null).next()) {
        if (connection.metaData.getTables(null, null, incorrectV30Name, null).next()) { throw DatabaseIncompatibleException(warning(incorrectV30Name, "3.0")) }
        if (connection.metaData.getTables(null, null, incorrectV31Name, null).next()) { throw DatabaseIncompatibleException(warning(incorrectV31Name, "3.1")) }
    }
}

private fun checkCorrectCheckpointTypeOnPostgres(connection: Connection) {
    val metaData = connection.metaData
    if (metaData.getDatabaseProductName() != "PostgreSQL") {
        return
    }

    val result = metaData.getColumns(null, null, "node_checkpoints", "checkpoint_value")
    if (result.next()) {
        val type = result.getString("TYPE_NAME")
        if (type != "bytea") {
            throw DatabaseIncompatibleException("The type of the 'checkpoint_value' table must be 'bytea', but 'oid' was found. See upgrade notes to migrate from Corda database version 3.1 https://docs.corda.net/head/upgrade-notes.html.")
        }
    }
}
