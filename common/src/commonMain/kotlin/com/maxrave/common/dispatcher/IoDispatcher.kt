package com.maxrave.common.dispatcher

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for work that was previously scheduled with Dispatchers.IO.
 * Platform implementations preserve IO dispatching where it is available.
 */
expect val ioDispatcher: CoroutineDispatcher
