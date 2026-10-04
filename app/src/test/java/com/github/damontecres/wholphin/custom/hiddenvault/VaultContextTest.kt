package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.idOf
import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.uuidOf
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultLockReason
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.ItemRef
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun vaultTest(block: suspend TestScope.(Device, FakeJellyfin) -> Unit) =
    runTest {
        val server = FakeJellyfin().apply { seedStandard() }
        val device = Device(server, backgroundScope)
        device.configure()
        server.reset()
        block(device, server)
    }

private suspend fun Device.refused(name: String): Boolean =
    try {
        api.userLibraryApi.getItem(uuidOf(name))
        false
    } catch (ex: InvalidStatusException) {
        ex.status == 404
    }

/**
 * Tag matches the index doesn't know yet, and tags outside every vault library
 */
class SuspectTest {
    @Test
    fun `an out of scope tag match costs one rebuild, then is remembered`() =
        vaultTest { device, server ->
            val builds = device.service.indexBuilds
            val shows = GetItemsRequest(parentId = uuidOf("lib-shows"), recursive = true, limit = 48)
            // s1 carries "ecchi", a rule of the anime vault, but sits in the shows library
            assertTrue(
                names(
                    device.api.itemsApi
                        .getItems(shows)
                        .content,
                ).contains("s1"),
            )
            assertEquals(builds + 1, device.service.indexBuilds)
            device.api.itemsApi.getItems(shows)
            device.api.tvShowsApi.getNextUp(GetNextUpRequest(limit = 15))
            assertEquals(builds + 1, device.service.indexBuilds)
            // Remembered across a restart
            device.start()
            assertTrue(
                names(
                    device.api.itemsApi
                        .getItems(shows)
                        .content,
                ).contains("s1"),
            )
            assertEquals(0, device.service.indexBuilds)
        }

    @Test
    fun `an item tagged after the index was built is caught at once`() =
        vaultTest { device, server ->
            server.series("lib-anime", "a7", "ecchi", created = "2025-01-01T00:00:00Z")
            val latest =
                device.api.userLibraryApi
                    .getLatestMedia(GetLatestMediaRequest(parentId = uuidOf("lib-anime"), limit = 15))
                    .content
            assertFalse(names(latest).contains("a7"))
            assertTrue(device.service.index!!.contains(idOf("a7")))
            // and its episodes follow through the series from now on
            server.episode("lib-anime", "a7e1", "a7", nextUp = true)
            assertFalse(
                names(
                    device.api.tvShowsApi
                        .getNextUp(GetNextUpRequest(limit = 15))
                        .content,
                ).contains("a7e1"),
            )
        }

    @Test
    fun `library change events refresh the index`() =
        vaultTest { device, server ->
            server.series("lib-shows", "s9", "Private")
            server.episode("lib-shows", "s9e1", "s9", nextUp = true)
            device.vault.onLibraryChanged()
            device.vault.onLibraryChanged()
            testScheduler.advanceTimeBy(HiddenVault.LIBRARY_CHANGE_DEBOUNCE_MS + 1)
            testScheduler.runCurrent()
            assertEquals(
                1,
                server.calls.count {
                    it.path == "/Items" &&
                        it.str("parentId")?.let(FakeJellyfin::normalize) == idOf("lib-shows")
                },
            )
            assertTrue(device.service.index!!.contains(idOf("s9")))
            assertFalse(
                names(
                    device.api.tvShowsApi
                        .getNextUp(GetNextUpRequest(limit = 15))
                        .content,
                ).contains("s9e1"),
            )
        }
}

/**
 * Unlocking a vault never changes the normal app; only item specific calls inside the open
 * vault see its content.
 */
class VaultContextTest {
    @Test
    fun `global lists stay filtered while a vault is open`() =
        vaultTest { device, _ ->
            device.enter("anime")
            assertEquals(
                listOf("a5e1", "s1e1"),
                names(
                    device.api.tvShowsApi
                        .getNextUp(GetNextUpRequest(limit = 15))
                        .content,
                ),
            )
            assertEquals(
                listOf("a5e2"),
                names(
                    device.api.itemsApi
                        .getResumeItems(GetResumeItemsRequest(limit = 15))
                        .content,
                ),
            )
            val latest =
                device.api.userLibraryApi
                    .getLatestMedia(GetLatestMediaRequest(parentId = uuidOf("lib-anime"), limit = 15))
                    .content
            assertFalse(names(latest).contains("a1"))
            val grid = device.api.itemsApi.getItems(GetItemsRequest(parentId = uuidOf("lib-anime"), recursive = true, limit = 48))
            assertFalse(names(grid.content).contains("a1"))
            val search = device.api.itemsApi.getItems(GetItemsRequest(searchTerm = "a1", recursive = true))
            assertTrue(search.content.items.isEmpty())
        }

    @Test
    fun `item specific calls inside the open vault see its content`() =
        vaultTest { device, _ ->
            device.enter("anime")
            assertFalse(device.refused("a1"))
            assertFalse(device.refused("a1e1"))
            assertEquals(
                listOf("a1e1"),
                names(
                    device.api.tvShowsApi
                        .getEpisodes(uuidOf("a1"))
                        .content,
                ),
            )
            assertEquals(
                listOf("a1-s1"),
                names(
                    device.api.tvShowsApi
                        .getSeasons(uuidOf("a1"))
                        .content,
                ),
            )
            val seasons =
                device.api.itemsApi
                    .getItems(GetItemsRequest(parentId = uuidOf("a1"), includeItemTypes = listOf(BaseItemKind.SEASON)))
                    .content
            assertEquals(listOf("a1-s1"), names(seasons))
            assertEquals(
                listOf("a1e1"),
                names(
                    device.api.tvShowsApi
                        .getNextUp(GetNextUpRequest(seriesId = uuidOf("a1"), limit = 5))
                        .content,
                ),
            )
        }

    @Test
    fun `the other vault stays closed`() =
        vaultTest { device, _ ->
            device.enter("anime")
            assertTrue(device.refused("s2"))
            assertTrue(device.refused("s2e1"))
            assertTrue(
                device.api.tvShowsApi
                    .getEpisodes(uuidOf("s2"))
                    .content.items
                    .isEmpty(),
            )
        }

    @Test
    fun `unlocking alone opens nothing`() =
        vaultTest { device, _ ->
            device.session.unlock(SCOPE, "anime")
            assertTrue(device.refused("a1"))
        }

    @Test
    fun `locking closes it again`() =
        vaultTest { device, _ ->
            device.enter("anime")
            assertFalse(device.refused("a1"))
            device.session.lock(SCOPE, "anime", VaultLockReason.MANUAL)
            assertTrue(device.refused("a1"))
        }

    @Test
    fun `a library is never an anchor, even inside the vault`() =
        vaultTest { device, _ ->
            device.enter("anime")
            val library = device.api.itemsApi.getItems(GetItemsRequest(parentId = uuidOf("lib-anime"), recursive = true))
            assertFalse(names(library.content).contains("a1"))
        }

    @Test
    fun `vault content counts as activity`() =
        vaultTest { device, _ ->
            device.enter("anime")
            device.now += 14 * 60_000
            device.api.userLibraryApi.getItem(uuidOf("a1e1"))
            device.now += 14 * 60_000
            device.session.checkTimeouts()
            assertTrue(device.session.isUnlocked(SCOPE, "anime"))
            device.now += 16 * 60_000
            device.session.checkTimeouts()
            assertFalse(device.session.isUnlocked(SCOPE, "anime"))
        }
}

/**
 * The second line: whatever arrives by id is checked again before it opens or plays.
 */
class GatesTest {
    @Test
    fun `hidden items are refused for playback outside their vault`() =
        vaultTest { device, _ ->
            assertTrue(device.api.refusesPlayback(uuidOf("a1")))
            assertTrue(device.api.refusesPlayback(uuidOf("a1e1")))
            assertTrue(device.api.refusesPlayback(uuidOf("f2")))
            assertFalse(device.api.refusesPlayback(uuidOf("a5e1")))
            assertFalse(device.api.refusesPlayback(ItemRef(idOf("x"), "Episode", seriesId = idOf("a5"))))
        }

    @Test
    fun `a mixed queue keeps only what may play`() =
        vaultTest { device, _ ->
            val queue =
                listOf(
                    ItemRef(idOf("a5e1"), "Episode", seriesId = idOf("a5")),
                    ItemRef(idOf("a1e1"), "Episode", seriesId = idOf("a1")),
                    ItemRef(idOf("m3"), "Movie"),
                    ItemRef(idOf("m1"), "Movie"),
                )
            val playable = queue.filter { !device.api.refusesPlayback(it) }.map { FakeJellyfin.nameOf(it.id!!) }
            assertEquals(listOf("a5e1", "m3"), playable)
        }

    @Test
    fun `direct playback by id is refused at the server call`() =
        vaultTest { device, server ->
            try {
                device.api.mediaInfoApi.getPostedPlaybackInfo(uuidOf("a1e1"))
                fail("played a hidden episode")
            } catch (ex: InvalidStatusException) {
                assertEquals(404, ex.status)
            }
            assertEquals(0, server.count("/Items/{itemId}/PlaybackInfo"))
            device.api.mediaInfoApi.getPostedPlaybackInfo(uuidOf("a5e1"))
            assertEquals(1, server.count("/Items/{itemId}/PlaybackInfo"))
        }

    @Test
    fun `inside the open vault its own content plays, the other vault stays shut`() =
        vaultTest { device, _ ->
            device.enter("anime")
            assertFalse(device.api.refusesPlayback(uuidOf("a1")))
            assertFalse(device.api.refusesPlayback(uuidOf("a1e1")))
            assertTrue(device.api.refusesPlayback(uuidOf("s2e1")))
            device.api.mediaInfoApi.getPostedPlaybackInfo(uuidOf("a1e1"))
            device.session.lock(SCOPE, "anime", VaultLockReason.MANUAL)
            assertTrue(device.api.refusesPlayback(uuidOf("a1e1")))
        }

    @Test
    fun `deep links and direct ids are refused`() =
        vaultTest { device, _ ->
            // A deep link resolves the id with getItem before navigating: refused
            assertTrue(device.refused("a2"))
            assertTrue(device.refused("a2e1"))
            assertTrue(device.refused("m2"))
            assertFalse(device.refused("m3"))
        }

    @Test
    fun `playback reports of vault content keep the vault open`() =
        vaultTest { device, _ ->
            device.enter("anime")
            device.api.userLibraryApi.getItem(uuidOf("a1e1"))
            repeat(4) {
                device.now += 10 * 60_000
                device.api.playStateReport(uuidOf("a1e1"))
                device.session.checkTimeouts()
            }
            assertTrue(device.session.isUnlocked(SCOPE, "anime"))
        }
}

private suspend fun org.jellyfin.sdk.api.client.ApiClient.playStateReport(itemId: java.util.UUID) {
    playStateApi.reportPlaybackProgress(
        PlaybackProgressInfo(
            canSeek = true,
            itemId = itemId,
            isPaused = false,
            isMuted = false,
            playMethod = PlayMethod.DIRECT_PLAY,
            repeatMode = RepeatMode.REPEAT_NONE,
            playbackOrder = PlaybackOrder.DEFAULT,
        ),
    )
}
