package com.maxrave.data.di.loader

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.repository.CacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

internal fun installIosMediaServiceBindings() {
    loadKoinModules(
        module {
            single<CoroutineScope>(qualifier = named(SERVICE_SCOPE), createdAtStart = true) {
                CoroutineScope(SupervisorJob() + Dispatchers.Main)
            }

            // iOS currently streams directly through AVPlayer and has no disk-backed player cache.
            single<CacheRepository> {
                object : CacheRepository {
                    override suspend fun getCacheSize(cacheName: String): Long = 0L

                    override fun clearCache(cacheName: String) = Unit

                    override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()

                    override suspend fun getFullyCachedSongIds(): List<String> = emptyList()

                    override suspend fun removeCachedSong(videoId: String) = Unit
                }
            }
        },
    )
}

actual fun loadMediaService() = installIosMediaServiceBindings()

actual fun loadLoginSyncModule() {
}
