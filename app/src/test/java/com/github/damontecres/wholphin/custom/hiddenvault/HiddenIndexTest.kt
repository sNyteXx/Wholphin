package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.idOf
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentIndex
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultStorageKeys
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenIndexTest {
    @Test
    fun `one tag query per configured library, results validated exactly`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            val tagQueries = server.calls.filter { it.path == "/Items" && it.list("tags") != null }
            assertEquals(4, tagQueries.size)
            assertEquals(
                setOf("lib-anime", "lib-anime-movies", "lib-shows", "lib-movies").map { idOf(it) }.toSet(),
                tagQueries.map { FakeJellyfin.normalize(it.str("parentId")!!) }.toSet(),
            )
            val index = device.service.index!!
            assertEquals(setOf("a1", "a2", "m1", "m2", "s2", "f2").map { idOf(it) }.toSet(), index.ids)
            // The server matched "Ecchi!" to "ecchi"; the exact check dropped it
            assertFalse(index.contains(idOf("a6")))
            assertEquals("anime", index[idOf("a1")]?.vaultId)
            assertEquals("shows", index[idOf("s2")]?.vaultId)
        }

    @Test
    fun `several tags of one library are one query`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            Device(server, backgroundScope).configure()
            val query =
                server.calls.single {
                    it.path == "/Items" &&
                        it.str("parentId")?.let(FakeJellyfin::normalize) == idOf("lib-anime-movies")
                }
            assertEquals(listOf("ecchi", "private"), query.list("tags"))
        }

    @Test
    fun `index is persisted and reused on the next start without a request`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            server.reset()
            device.start()
            assertNotNull(device.service.index)
            device.service.ensureReady()
            assertEquals(0, server.calls.size)
        }

    @Test
    fun `a stale index is rebuilt once in the background`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            server.reset()
            device.now += HiddenContentService.STALE_AFTER_MS + 60_000
            device.service.ensureReady()
            device.service.ensureReady()
            device.service.refreshIndex()
            assertEquals(4, server.calls.count { it.path == "/Items" && it.list("tags") != null })
        }

    @Test
    fun `changing the rules throws the old index away`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            val before = device.service.fingerprint
            device.configure(standardConfig().copy(vaults = standardConfig().vaults.take(1)))
            assertTrue(before != device.service.fingerprint)
            assertFalse(device.service.index!!.contains(idOf("s2")))
            assertTrue(device.service.index!!.contains(idOf("a1")))
        }

    @Test
    fun `a library deleted on the server holds nothing to hide`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            server.deletedLibraries += "lib-movies"
            val device = Device(server, backgroundScope)
            device.configure()
            val index = device.service.index!!
            assertTrue(index.contains(idOf("a1")))
            assertFalse(index.contains(idOf("f2")))
        }

    @Test
    fun `a library failing for a moment keeps what it hid before`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            server.failingLibraries += "lib-shows"
            device.service.refreshIndex()
            assertTrue(device.service.index!!.contains(idOf("s2")))
        }

    @Test
    fun `a failing first build doesn't stall every list`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            server.failingLibraries += "lib-shows"
            device.configure()
            assertEquals(null, device.service.index)
            server.reset()
            // tag only fallback, and no rebuild per request while the failure is fresh
            val latest =
                device.api.userLibraryApi
                    .getLatestMedia(GetLatestMediaRequest(parentId = FakeJellyfin.uuidOf("lib-anime"), limit = 15))
                    .content
            assertFalse(names(latest).contains("a1"))
            device.api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(parentId = FakeJellyfin.uuidOf("lib-anime"), limit = 15))
            assertEquals(0, server.calls.count { it.list("tags") != null })
            // later it tries again
            server.failingLibraries.clear()
            device.now += HiddenContentService.RETRY_AFTER_FAILURE_MS + 1
            device.service.ensureReady()
            assertTrue(device.service.index!!.contains(idOf("s2")))
        }

    @Test
    fun `index codec keeps vaults and libraries`() {
        val index =
            HiddenContentIndex(
                "fp",
                42L,
                mapOf(
                    "x" to
                        com.github.damontecres.wholphin.custom.hiddenvault.data
                            .HiddenEntry("anime", "lib"),
                ),
                setOf("lib", "other"),
            )
        val decoded = HiddenContentIndex.decode(index.encode())!!
        assertEquals("anime", decoded["x"]?.vaultId)
        assertEquals("lib", decoded["x"]?.libraryId)
        assertEquals(setOf("lib", "other"), decoded.knownLibraryIds)
        assertEquals(42L, decoded.builtAtMs)
    }

    @Test
    fun `the store never holds an unlocked state`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            device.enter("anime")
            device.vault.pins.set(SCOPE, "1234")
            val keys = device.store.keys()
            assertTrue(keys.none { it.contains("unlock") })
            assertTrue(keys.contains(VaultStorageKeys.config(SCOPE)))
            assertTrue(keys.contains(VaultStorageKeys.index(SCOPE)))
            assertTrue(
                device.store.values.values
                    .none { it.contains("\"anime\"") && it.contains("unlocked") },
            )
        }
}
