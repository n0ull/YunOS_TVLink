package app.tvlink.proto.idc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * IDC session crypto — mirrors IdcEncryptionHelper / CipherUtils.
 * Note: the original HmacSHA256 helper ignores its second argument (key = data = first arg);
 * that quirk is part of the key-derivation and must be preserved.
 *
 * TODO: This entire path is DEAD CODE — the client always negotiates encryption_algorithm_ver=0
 * (plaintext). deriveAesSecret's flip/putInt may be bug-compatible with the original; do NOT
 * "fix" without a ver≠0 TV to validate against. Existing test pins current behavior.
 */
@Suppress("unused")
object IdcCrypto {
    private const val INIT_KEY_HEX = "a31c5c871c597d133cb15cd68fefdc1a"
    private const val SEED_XOR_CONST = 51550860

    fun deriveAesSecret(clientSeed: Int, serverSeed: Int): ByteArray {
        val keyBytes = hexToBytes(INIT_KEY_HEX)
        val buf = ByteBuffer.allocate(keyBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(keyBytes)
        buf.flip()
        buf.putInt(clientSeed xor SEED_XOR_CONST xor serverSeed)
        val selfMac = hmacSha256Self(buf.array())
        return selfMac.copyOf(16)
    }

    fun seedDigest(seed: Int): String {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seed)
        return toHex(hmacSha256Self(buf.array()))
    }

    /** HMAC-SHA256 where key and message are the same bytes (matches original bug-compatible behavior). */
    private fun hmacSha256Self(keyAndData: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyAndData, "HmacSHA256"))
        return mac.doFinal(keyAndData)
    }

    /** AES/CBC/PKCS5Padding, IV == key (matches original). */
    fun aesEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return c.doFinal(data)
    }

    fun aesDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
        return c.doFinal(data)
    }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
