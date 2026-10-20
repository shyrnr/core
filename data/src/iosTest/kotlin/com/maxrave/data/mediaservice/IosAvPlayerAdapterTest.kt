package com.maxrave.data.mediaservice

import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosAvPlayerAdapterTest {
    @Test
    fun queueSelectionTracksCurrentItemAndPreviousNavigation() {
        val scope = CoroutineScope(SupervisorJob())
        val player = IosAvPlayerAdapter(scope) { null }
        val first = mediaItem("first")
        val second = mediaItem("second")

        try {
            player.setMediaItem(first)
            player.addMediaItem(second)

            assertEquals(2, player.mediaItemCount)
            assertEquals(0, player.currentMediaItemIndex)
            assertEquals(first, player.currentMediaItem)
            assertTrue(player.hasNextMediaItem())

            player.seekTo(1, 0L)
            assertEquals(1, player.currentMediaItemIndex)
            assertEquals(second, player.currentMediaItem)
            assertTrue(player.hasPreviousMediaItem())

            player.seekToPreviousMediaItem()
            assertEquals(0, player.currentMediaItemIndex)
            assertEquals(first, player.currentMediaItem)
        } finally {
            player.release()
            scope.cancel()
        }
    }

    @Test
    fun videoTaggedMediaIdIsNormalizedBeforeStreamLookup() {
        val item = mediaItem("${com.maxrave.common.MERGING_DATA_TYPE.VIDEO}video-id")

        assertEquals("video-id", videoIdForStream(item))
    }

    private fun mediaItem(id: String) =
        GenericMediaItem(
            mediaId = id,
            uri = id,
            metadata = GenericMediaMetadata(title = id),
        )
}
