package com.maxrave.common.dispatcher

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Dispatchers.IO is internal in the resolved Kotlin/Native artifact; use its public background dispatcher.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
