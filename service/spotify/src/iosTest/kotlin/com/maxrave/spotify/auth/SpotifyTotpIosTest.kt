package com.maxrave.spotify.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyTotpIosTest {
    @Test
    fun generatesRfc6238Sha1VectorFromBase32Secret() {
        assertEquals(
            "287082",
            generateTotp("gezdgnbvgy3tqojqgezdgnbvgy3tqojq====", timestamp = 59_000L),
        )
    }
}
