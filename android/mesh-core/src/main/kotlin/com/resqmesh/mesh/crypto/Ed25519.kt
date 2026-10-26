package com.resqmesh.mesh.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Ed25519 (RFC 8032) through BouncyCastle's lightweight API, which behaves the same on
 * Android and on a desktop JVM. Keys are raw 32-byte arrays; signatures are 64 bytes.
 */
object Ed25519 {
    const val PRIVATE_KEY_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    fun generatePrivateKey(random: SecureRandom): ByteArray = Ed25519PrivateKeyParameters(random).encoded

    fun publicKeyOf(privateKey: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) { "Ed25519 private key must be $PRIVATE_KEY_SIZE bytes" }
        return Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == PRIVATE_KEY_SIZE) { "Ed25519 private key must be $PRIVATE_KEY_SIZE bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Never throws: any malformed key or signature simply fails verification. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE || signature.size != SIGNATURE_SIZE) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: RuntimeException) {
            false
        }
    }
}
