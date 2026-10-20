package com.maxrave.ktorext.encoding

import kotlin.test.Test
import kotlin.test.assertEquals

class BrotliEncoderIosTest {
    @Test
    fun factoryReturnsBrotliContentEncoder() {
        assertEquals("br", createBrotliEncoder().name)
    }
}
