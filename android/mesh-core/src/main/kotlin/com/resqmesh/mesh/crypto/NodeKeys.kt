package com.resqmesh.mesh.crypto

import com.resqmesh.mesh.Base64Url
import java.security.SecureRandom

/**
 * A phone's (or the server's) Ed25519 key pair. On Android the app stores [exportPrivateKey]'s
 * bytes encrypted under an Android Keystore key and rebuilds this with [fromPrivateKey].
 */
class NodeKeys private constructor(private val privateKey: ByteArray) {
    val publicKey: ByteArray = Ed25519.publicKeyOf(privateKey)
    val publicKeyB64: String = Base64Url.encode(publicKey)
    val nodeId: NodeId = NodeId.of(publicKey)

    fun sign(message: ByteArray): ByteArray = Ed25519.sign(privateKey, message)

    fun exportPrivateKey(): ByteArray = privateKey.copyOf()

    override fun toString(): String = "NodeKeys(${nodeId.short})"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): NodeKeys = NodeKeys(Ed25519.generatePrivateKey(random))

        fun fromPrivateKey(privateKey: ByteArray): NodeKeys {
            require(privateKey.size == Ed25519.PRIVATE_KEY_SIZE) { "Ed25519 private key must be ${Ed25519.PRIVATE_KEY_SIZE} bytes" }
            return NodeKeys(privateKey.copyOf())
        }
    }
}
