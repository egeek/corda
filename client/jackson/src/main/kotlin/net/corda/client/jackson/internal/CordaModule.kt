@file:Suppress("DEPRECATION")

package net.corda.client.jackson.internal

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.annotation.JsonCreator.Mode.*
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import net.corda.client.jackson.JacksonSupport
import net.corda.core.contracts.Amount
import net.corda.core.crypto.DigitalSignature
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.*
import net.corda.core.internal.DigitalSignatureWithCert
import net.corda.core.node.NodeInfo
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.ByteSequence
import net.corda.core.utilities.NetworkHostAndPort
import java.math.BigDecimal
import java.security.PublicKey
import java.security.cert.CertPath
import java.util.*

class CordaModule : SimpleModule("corda-core") {
    override fun setupModule(context: SetupContext) {
        super.setupModule(context)

        context.addBeanDeserializerModifier(AmountBeanDeserializerModifier())

        context.setMixInAnnotations(PartyAndCertificate::class.java, PartyAndCertificateMixin::class.java)
        context.setMixInAnnotations(NetworkHostAndPort::class.java, NetworkHostAndPortMixin::class.java)
        context.setMixInAnnotations(CordaX500Name::class.java, CordaX500NameMixin::class.java)
        context.setMixInAnnotations(Amount::class.java, AmountMixin::class.java)
        context.setMixInAnnotations(AbstractParty::class.java, AbstractPartyMixin::class.java)
        context.setMixInAnnotations(AnonymousParty::class.java, AnonymousPartyMixin::class.java)
        context.setMixInAnnotations(Party::class.java, PartyMixin::class.java)
        context.setMixInAnnotations(PublicKey::class.java, PublicKeyMixin::class.java)
        context.setMixInAnnotations(ByteSequence::class.java, ByteSequenceMixin::class.java)
        context.setMixInAnnotations(SecureHash.SHA256::class.java, SecureHashSHA256Mixin::class.java)
        context.setMixInAnnotations(SerializedBytes::class.java, SerializedBytesMixin::class.java)
        context.setMixInAnnotations(DigitalSignature.WithKey::class.java, ByteSequenceWithPropertiesMixin::class.java)
        context.setMixInAnnotations(DigitalSignatureWithCert::class.java, ByteSequenceWithPropertiesMixin::class.java)
        context.setMixInAnnotations(TransactionSignature::class.java, ByteSequenceWithPropertiesMixin::class.java)
        context.setMixInAnnotations(SignedTransaction::class.java, JacksonSupport.SignedTransactionMixin::class.java)
        context.setMixInAnnotations(WireTransaction::class.java, JacksonSupport.WireTransactionMixin::class.java)
        context.setMixInAnnotations(NodeInfo::class.java, NodeInfoMixin::class.java)
    }
}

@ToStringSerialize
@JsonDeserialize(using = NetworkHostAndPortDeserializer::class)
private interface NetworkHostAndPortMixin

private class NetworkHostAndPortDeserializer : JsonDeserializer<NetworkHostAndPort>() {
    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): NetworkHostAndPort {
        return NetworkHostAndPort.parse(parser.text)
    }
}

@JsonSerialize(using = PartyAndCertificateSerializer::class)
// TODO Add deserialization which follows the same lookup logic as Party
private interface PartyAndCertificateMixin

private class PartyAndCertificateSerializer : JsonSerializer<PartyAndCertificate>() {
    override fun serialize(value: PartyAndCertificate, gen: JsonGenerator, serializers: SerializerProvider) {
        val mapper = gen.codec as JacksonSupport.PartyObjectMapper
        if (mapper.isFullParties) {
            gen.writeObject(PartyAndCertificateWrapper(value.name, value.certPath))
        } else {
            gen.writeObject(value.party)
        }
    }
}

private class PartyAndCertificateWrapper(val name: CordaX500Name, val certPath: CertPath)

@JsonSerialize(using = SerializedBytesSerializer::class)
@JsonDeserialize(using = SerializedBytesDeserializer::class)
private class SerializedBytesMixin

private class SerializedBytesSerializer : JsonSerializer<SerializedBytes<*>>() {
    override fun serialize(value: SerializedBytes<*>, gen: JsonGenerator, serializers: SerializerProvider) {
        val deserialized = value.deserialize<Any>()
        gen.jsonObject {
            writeStringField("class", deserialized.javaClass.name)
            writeObjectField("deserialized", deserialized)
        }
    }
}

private class SerializedBytesDeserializer : JsonDeserializer<SerializedBytes<*>>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): SerializedBytes<Any> {
        return if (parser.currentToken == JsonToken.START_OBJECT) {
            val mapper = parser.codec as ObjectMapper
            val json = parser.readValueAsTree<ObjectNode>()
            val clazz = context.findClass(json["class"].textValue())
            val pojo = mapper.convertValue(json["deserialized"], clazz)
            pojo.serialize()
        } else {
            SerializedBytes(parser.binaryValue)
        }
    }
}

@JsonDeserialize(using = JacksonSupport.PartyDeserializer::class)
private interface AbstractPartyMixin

@JsonSerialize(using = JacksonSupport.AnonymousPartySerializer::class)
@JsonDeserialize(using = JacksonSupport.AnonymousPartyDeserializer::class)
private interface AnonymousPartyMixin

@JsonSerialize(using = JacksonSupport.PartySerializer::class)
private interface PartyMixin

@ToStringSerialize
@JsonDeserialize(using = JacksonSupport.CordaX500NameDeserializer::class)
private interface CordaX500NameMixin

@JsonIgnoreProperties("legalIdentities")  // This is already covered by legalIdentitiesAndCerts
@JsonDeserialize(using = JacksonSupport.NodeInfoDeserializer::class)
private interface NodeInfoMixin

@ToStringSerialize
@JsonDeserialize(using = JacksonSupport.SecureHashDeserializer::class)
private interface SecureHashSHA256Mixin

@JsonSerialize(using = JacksonSupport.PublicKeySerializer::class)
@JsonDeserialize(using = JacksonSupport.PublicKeyDeserializer::class)
private interface PublicKeyMixin

@Suppress("unused_parameter")
@ToStringSerialize
private abstract class AmountMixin @JsonCreator(mode = DISABLED) constructor(
    quantity: Long,
    displayTokenSize: BigDecimal,
    token: Any
) {
    /**
     * This mirrors the [Amount] constructor that we want Jackson to use, and
     * requires that we also tell Jackson NOT to use [Amount]'s primary constructor.
     */
    @JsonCreator constructor(
        @JsonProperty("quantity")
        quantity: Long,

        @JsonDeserialize(using = TokenDeserializer::class)
        @JsonProperty("token")
        token: Any
    ) : this(quantity, Amount.getDisplayTokenSize(token), token)
}

/**
 * Implements polymorphic deserialization for [Amount.token]. Kotlin must
 * be able to determine the concrete [Amount] type at runtime, or it will
 * fall back to using [Currency].
 */
private class TokenDeserializer(private val tokenType: Class<*>) : JsonDeserializer<Any>(), ContextualDeserializer {
    @Suppress("unused")
    constructor() : this(Currency::class.java)

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): Any = parser.readValueAs(tokenType)

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): TokenDeserializer {
        if (property == null) return this
        return TokenDeserializer(property.type.rawClass.let { type ->
            if (type == Any::class.java) Currency::class.java else type
        })
    }
}

/**
 * Intercepts bean-based deserialization for the generic [Amount] type.
 */
private class AmountBeanDeserializerModifier : BeanDeserializerModifier() {
    override fun modifyDeserializer(config: DeserializationConfig, description: BeanDescription, deserializer: JsonDeserializer<*>): JsonDeserializer<*> {
        val modified = super.modifyDeserializer(config, description, deserializer)
        return if (Amount::class.java.isAssignableFrom(description.beanClass)) {
            AmountDeserializer(modified)
        } else {
            modified
        }
    }
}

private class AmountDeserializer(delegate: JsonDeserializer<*>) : DelegatingDeserializer(delegate) {
    override fun newDelegatingInstance(newDelegatee: JsonDeserializer<*>) = AmountDeserializer(newDelegatee)

    override fun deserialize(parser: JsonParser, context: DeserializationContext?): Any {
        return if (parser.currentToken() == JsonToken.VALUE_STRING) {
            /*
             * This is obviously specific to Amount<Currency>, and is here to
             * preserve the original deserializing behaviour for this case.
             */
            Amount.parseCurrency(parser.text)
        } else {
            /*
             * Otherwise continue deserializing our Bean as usual.
             */
            _delegatee.deserialize(parser, context)
        }
    }
}

@JsonDeserialize(using = JacksonSupport.OpaqueBytesDeserializer::class)
@JsonSerialize(using = ByteSequenceSerializer::class)
private interface ByteSequenceMixin

private class ByteSequenceSerializer : JsonSerializer<ByteSequence>() {
    override fun serialize(value: ByteSequence, gen: JsonGenerator, serializers: SerializerProvider) {
        val bytes = value.bytes
        gen.writeBinary(bytes, value.offset, value.size)
    }
}

@JsonIgnoreProperties("offset", "size")
@JsonSerialize
@JsonDeserialize
private interface ByteSequenceWithPropertiesMixin
