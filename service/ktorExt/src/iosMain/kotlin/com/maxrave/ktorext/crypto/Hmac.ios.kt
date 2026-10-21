@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.maxrave.ktorext.crypto

import com.maxrave.ktorext.crypto.commoncrypto.CCHmac
import com.maxrave.ktorext.crypto.commoncrypto.kCCHmacAlgMD5
import com.maxrave.ktorext.crypto.commoncrypto.kCCHmacAlgSHA1
import com.maxrave.ktorext.crypto.commoncrypto.kCCHmacAlgSHA224
import com.maxrave.ktorext.crypto.commoncrypto.kCCHmacAlgSHA256
import com.maxrave.ktorext.crypto.commoncrypto.kCCHmacAlgSHA384
import com.maxrave.ktorext.crypto.commoncrypto.kCCHmacAlgSHA512
import kotlinx.cinterop.convert
import kotlinx.cinterop.refTo
import platform.Foundation.NSDate
import kotlin.io.encoding.Base64

actual class Hmac actual constructor(
    private val algorithm: String,
    secretKey: String,
) {
    private val key = secretKey.encodeToByteArray()
    private val tokenTtl = 300_000L
    private val normalizedAlgorithm = algorithm.uppercase().replace("-", "").replace("_", "")

    actual fun getMacTimestampPair(uri: String): Pair<String, String> {
        val timestamp = (NSDate().timeIntervalSince1970 * 1_000.0).toLong().toString()
        return generateHmac("$timestamp$uri") to timestamp
    }

    actual fun generateHmac(data: String): String =
        Base64.encode(calculateHmac(data.encodeToByteArray()))

    actual fun validateHmac(
        data: String,
        hmac: String,
    ): Boolean {
        val expected = generateHmac(data).encodeToByteArray()
        val supplied = hmac.encodeToByteArray()
        if (expected.size != supplied.size) return false

        var difference = 0
        for (index in expected.indices) {
            difference = difference or (expected[index].toInt() xor supplied[index].toInt())
        }
        return difference == 0
    }

    actual fun isValidTimestamp(timestamp: String): Boolean {
        val requestTime = timestamp.toLongOrNull() ?: return false
        val currentTime = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
        return currentTime - requestTime < tokenTtl
    }

    private fun calculateHmac(data: ByteArray): ByteArray {
        val outputSize =
            when (normalizedAlgorithm) {
                "HMACMD5", "MD5" -> 16
                "HMACSHA1", "SHA1" -> 20
                "HMACSHA224", "SHA224" -> 28
                "HMACSHA256", "SHA256" -> 32
                "HMACSHA384", "SHA384" -> 48
                "HMACSHA512", "SHA512" -> 64
                else -> throw IllegalArgumentException("Unsupported HMAC algorithm: $algorithm")
            }
        val result = ByteArray(outputSize)
        val safeKey = key.ifEmpty { byteArrayOf(0) }
        val safeData = data.ifEmpty { byteArrayOf(0) }

        val keyPointer = safeKey.refTo(0)
        val dataPointer = safeData.refTo(0)
        val resultPointer = result.refTo(0)
        val keyLength = key.size.convert()
        val dataLength = data.size.convert()

        when (normalizedAlgorithm) {
            "HMACMD5", "MD5" ->
                CCHmac(kCCHmacAlgMD5, keyPointer, keyLength, dataPointer, dataLength, resultPointer)
            "HMACSHA1", "SHA1" ->
                CCHmac(kCCHmacAlgSHA1, keyPointer, keyLength, dataPointer, dataLength, resultPointer)
            "HMACSHA224", "SHA224" ->
                CCHmac(kCCHmacAlgSHA224, keyPointer, keyLength, dataPointer, dataLength, resultPointer)
            "HMACSHA256", "SHA256" ->
                CCHmac(kCCHmacAlgSHA256, keyPointer, keyLength, dataPointer, dataLength, resultPointer)
            "HMACSHA384", "SHA384" ->
                CCHmac(kCCHmacAlgSHA384, keyPointer, keyLength, dataPointer, dataLength, resultPointer)
            "HMACSHA512", "SHA512" ->
                CCHmac(kCCHmacAlgSHA512, keyPointer, keyLength, dataPointer, dataLength, resultPointer)
            else -> throw IllegalArgumentException("Unsupported HMAC algorithm: $algorithm")
        }
        return result
    }
}
