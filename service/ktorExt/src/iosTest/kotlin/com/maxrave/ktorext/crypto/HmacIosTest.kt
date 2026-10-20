package com.maxrave.ktorext.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacIosTest {
    @Test
    fun generatesRfc4231Sha256Vector() {
        val hmac = Hmac("HmacSHA256", "Jefe")

        assertEquals(
            "W9zBRr9gdU5qBCQmCJV1x1oAPwidJzmDnexYuWTsOEM=",
            hmac.generateHmac("what do ya want for nothing?"),
        )
    }

    @Test
    fun validatesAndRejectsModifiedSignature() {
        val hmac = Hmac("HmacSHA256", "Jefe")
        val data = "what do ya want for nothing?"
        val signature = hmac.generateHmac(data)

        assertTrue(hmac.validateHmac(data, signature))
        assertFalse(hmac.validateHmac(data, signature.dropLast(1) + "A"))
    }
}
