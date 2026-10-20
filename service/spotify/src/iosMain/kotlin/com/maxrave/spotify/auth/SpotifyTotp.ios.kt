@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.maxrave.spotify.auth

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CommonCrypto.CCHmac
import platform.CommonCrypto.kCCHmacAlgSHA1

actual fun generateTotp(
    secret: String,
    timestamp: Long,
): String {
    val counter = timestamp / 30_000L
    val counterBytes = ByteArray(Long.SIZE_BYTES) { index ->
        (counter ushr ((Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
    }
    val keyBytes = decodeBase32Secret(secret)
    val safeKeyBytes = keyBytes.ifEmpty { byteArrayOf(0) }
    val digest = ByteArray(20)

    safeKeyBytes.usePinned { keyPinned ->
        counterBytes.usePinned { counterPinned ->
            digest.usePinned { digestPinned ->
                CCHmac(
                    kCCHmacAlgSHA1,
                    keyPinned.addressOf(0),
                    keyBytes.size.convert(),
                    counterPinned.addressOf(0),
                    counterBytes.size.convert(),
                    digestPinned.addressOf(0),
                )
            }
        }
    }

    val offset = digest.last().toInt() and 0x0f
    val binary =
        ((digest[offset].toInt() and 0x7f) shl 24) or
            ((digest[offset + 1].toInt() and 0xff) shl 16) or
            ((digest[offset + 2].toInt() and 0xff) shl 8) or
            (digest[offset + 3].toInt() and 0xff)
    return (binary % 1_000_000).toString().padStart(6, '0')
}

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private fun decodeBase32Secret(secret: String): ByteArray {
    val decoded = ArrayList<Byte>(secret.length * 5 / 8)
    var buffer = 0
    var bitCount = 0

    for (character in secret.uppercase()) {
        if (character == '=' || character.isWhitespace()) continue
        val value = BASE32_ALPHABET.indexOf(character)
        require(value >= 0) { "Invalid Base32 character in TOTP secret" }
        buffer = (buffer shl 5) or value
        bitCount += 5
        if (bitCount >= Byte.SIZE_BITS) {
            bitCount -= Byte.SIZE_BITS
            decoded += ((buffer shr bitCount) and 0xff).toByte()
        }
    }

    return decoded.toByteArray()
}
