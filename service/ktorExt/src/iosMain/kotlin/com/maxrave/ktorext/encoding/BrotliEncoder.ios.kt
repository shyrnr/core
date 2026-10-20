package com.maxrave.ktorext.encoding

import com.ensody.kompressor.brotli.ktor.BrotliContentEncoder
import io.ktor.util.ContentEncoder

/** KMP Brotli codec shared by all configured Apple targets. */
object BrotliEncoder : ContentEncoder by BrotliContentEncoder()

actual fun createBrotliEncoder(): ContentEncoder = BrotliEncoder
