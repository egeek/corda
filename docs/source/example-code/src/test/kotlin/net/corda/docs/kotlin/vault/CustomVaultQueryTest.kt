package net.corda.docs.kotlin.vault

import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.identity.Party
import net.corda.core.internal.packageName
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.*
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.docs.kotlin.tutorial.helloworld.IOUFlow
import net.corda.finance.*
import net.corda.finance.workflows.getCashBalances
import net.corda.finance.flows.CashIssueFlow
import net.corda.node.services.vault.VaultSchemaV1
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.StartedMockNode
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.*

class CustomVaultQueryTest {
    private lateinit var mockNet: MockNetwork
    private lateinit var nodeA: StartedMockNode
    private lateinit var nodeB: StartedMockNode
    private lateinit var notary: Party

    @Before
    fun setup() {
        mockNet = MockNetwork(threadPerNode = true, cordappPackages = listOf("net.corda.finance", IOUFlow::class.packageName, javaClass.packageName, "com.template"))
        nodeA = mockNet.createPartyNode()
        nodeB = mockNet.createPartyNode()
        notary = mockNet.defaultNotaryIdentity
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
    }

    @Test
    fun `query by max recorded time`() {
        nodeA.startFlow(IOUFlow(1000, nodeB.info.singleIdentity())).getOrThrow()
        nodeA.startFlow(IOUFlow(500, nodeB.info.singleIdentity())).getOrThrow()

        val max = builder { VaultSchemaV1.VaultStates::recordedTime.max() }
        val maxCriteria = QueryCriteria.VaultCustomQueryCriteria(max)

        val results = nodeA.transaction {
            val pageSpecification = PageSpecification(pageNumber = DEFAULT_PAGE_NUM, pageSize = DEFAULT_PAGE_SIZE)
            nodeA.services.vaultService.queryBy<ContractState>(criteria = maxCriteria, paging = pageSpecification)
        }
        assertThatCode { results.otherResults.single() }.doesNotThrowAnyException()
    }

    @Test
    fun `test custom vault query`() {
        // issue some cash in several currencies
        issueCashForCurrency(POUNDS(1000))
        issueCashForCurrency(DOLLARS(900))
        issueCashForCurrency(SWISS_FRANCS(800))
        val (cashBalancesOriginal, _) = getBalances()

        // top up all currencies (by double original)
        topUpCurrencies()
        val (cashBalancesAfterTopup, _) = getBalances()

        assertEquals(cashBalancesOriginal[GBP]?.times(2), cashBalancesAfterTopup[GBP])
        assertEquals(cashBalancesOriginal[USD]?.times(2)  , cashBalancesAfterTopup[USD])
        assertEquals(cashBalancesOriginal[CHF]?.times( 2), cashBalancesAfterTopup[CHF])
    }

    private fun issueCashForCurrency(amountToIssue: Amount<Currency>) {
        // Use NodeA as issuer and create some dollars
        nodeA.startFlow(CashIssueFlow(amountToIssue,
                OpaqueBytes.of(0x01),
                notary)).getOrThrow()
    }

    private fun topUpCurrencies() {
        nodeA.startFlow(TopupIssuerFlow.TopupIssuanceRequester(
                nodeA.info.singleIdentity(),
                OpaqueBytes.of(0x01),
                nodeA.info.singleIdentity(),
                notary)
        ).getOrThrow()
    }

    private fun getBalances(): Pair<Map<Currency, Amount<Currency>>, Map<Currency, Amount<Currency>>> {
        // Print out the balances
        val balancesNodesA = nodeA.transaction {
            nodeA.services.getCashBalances()
        }
        println("BalanceA\n$balancesNodesA")

        val balancesNodesB = nodeB.transaction {
            nodeB.services.getCashBalances()
        }
        println("BalanceB\n$balancesNodesB")

        return Pair(balancesNodesA, balancesNodesB)
    }
}
