package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.idOf
import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.uuidOf
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentIndex
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenEntry
import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenTagPolicy
import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultDefinition
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultLibrary
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.ItemRef
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceTest {
    /**
     * A catalog of realistic size: four libraries, ~1,200 series with 30 episodes each in the
     * anime library, and several hundred distinct tags.
     */
    private fun FakeJellyfin.realistic() {
        library("lib-anime", "tvshows")
        library("lib-anime-movies", "movies")
        library("lib-shows", "tvshows")
        library("lib-movies", "movies")
        for (i in 0 until 1200) {
            val tags = if (i % 5 == 0) arrayOf("Ecchi", "Genre ${i % 300}") else arrayOf("Genre ${i % 300}")
            series("lib-anime", "anime-$i", *tags)
            for (e in 0 until 30) {
                episode(
                    "lib-anime",
                    "anime-$i-e$e",
                    "anime-$i",
                    nextUp = e == 0 && i % 20 == 0,
                    resume =
                        e == 1 && i % 40 == 0,
                )
            }
        }
        for (i in 0 until 400) movie("lib-anime-movies", "amovie-$i", if (i % 14 == 0) "ecchi" else "Drama", resume = i % 50 == 0)
        for (i in 0 until 800) {
            series("lib-shows", "show-$i", if (i % 40 == 0) "private" else "Crime")
            episode("lib-shows", "show-$i-e0", "show-$i", nextUp = i % 30 == 0)
        }
        for (i in 0 until 1500) movie("lib-movies", "movie-$i", if (i % 70 == 0) "adult" else "adult animation")
    }

    /** Many tags per library, as a big tag picker would produce */
    private fun manyTags() =
        HiddenVaultConfig(
            vaults =
                listOf(
                    VaultDefinition(
                        "anime",
                        "Anime",
                        listOf(
                            VaultLibrary(lib("lib-anime"), "Anime", "tvshows", listOf("ecchi") + (0 until 250).map { "unused tag $it" }),
                            VaultLibrary(
                                lib("lib-anime-movies"),
                                "Filme (Anime)",
                                "movies",
                                listOf("ecchi") + (0 until 250).map { "other $it" },
                            ),
                        ),
                    ),
                    VaultDefinition(
                        "shows",
                        "Serien",
                        listOf(
                            VaultLibrary(lib("lib-shows"), "Serien", "tvshows", listOf("private")),
                            VaultLibrary(lib("lib-movies"), "Filme", "movies", listOf("adult")),
                        ),
                    ),
                ),
        )

    @Test
    fun `request counts stay bounded on a realistic catalog`() =
        runTest {
            val server = FakeJellyfin().apply { realistic() }
            val device = Device(server, backgroundScope)
            device.configure(manyTags())
            // One tag query per configured library (plus the library list)
            assertEquals(4, server.calls.count { it.list("tags") != null })
            assertEquals(240 + 29 + 20 + 22, device.service.index!!.size)

            suspend fun home() {
                device.api.itemsApi.getResumeItems(GetResumeItemsRequest(limit = 25))
                device.api.tvShowsApi.getNextUp(GetNextUpRequest(limit = 25))
                for (library in listOf("lib-anime", "lib-anime-movies", "lib-shows", "lib-movies")) {
                    device.api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(parentId = uuidOf(library), limit = 16))
                }
            }
            server.reset()
            home()
            val home = server.calls.size
            // 6 rows, at most a little read ahead where rows lost items; no per item lookups
            assertTrue("home cost $home requests", home <= 6 + 3)
            assertEquals(0, server.count("/Items/{itemId}"))
            assertEquals(0, server.calls.count { it.list("ids") != null })

            // The next app start answers from the stored index without rebuilding it
            device.start()
            server.reset()
            home()
            assertTrue(server.calls.none { it.list("tags") != null })

            // No request per episode: 30 episodes of a visible series cost one request
            server.reset()
            val episodes = device.api.tvShowsApi.getEpisodes(uuidOf("anime-1"))
            assertEquals(30, episodes.content.items.size)
            assertEquals(1, server.calls.size)

            // Search: nothing hidden, no lookups; a result that lost most of its hits to the
            // vault reads ahead within the budget
            server.reset()
            val search =
                device.api.itemsApi
                    .getItems(GetItemsRequest(searchTerm = "anime-10", recursive = true, limit = 50))
                    .content
            assertTrue(
                names(search).none {
                    device.service.index!!.contains(idOf(it)) ||
                        device.service.index!!.contains(idOf(it.substringBefore("-e")))
                },
            )
            assertTrue(server.calls.size <= 1 + 3)
            assertTrue(server.calls.all { it.path == "/Items" && it.list("ids") == null })
            server.reset()
            device.api.itemsApi.getItems(GetItemsRequest(searchTerm = "anime-11", recursive = true, limit = 50))
            assertEquals(1, server.calls.size)
        }

    @Test
    fun `a check is a hash lookup, not a scan over tags`() {
        val tags = (0 until 500).map { "tag $it" }
        val config =
            HiddenVaultConfig(
                vaults = (0 until 10).map { v -> VaultDefinition("v$v", "V$v", listOf(VaultLibrary(lib("lib-$v"), tags = tags))) },
            )
        val policy = HiddenTagPolicy.from(config)
        val entries = (0 until 50_000).associate { idOf("hidden-$it") to HiddenEntry("v${it % 10}", lib("lib-${it % 10}")) }
        val index = HiddenContentIndex(policy.fingerprint, 0, entries)
        val refs =
            (0 until 200_000).map {
                if (it % 2 == 0) {
                    ItemRef(idOf("visible-$it"), "Episode", seriesId = idOf("series-$it"), tags = listOf("Drama", "tag x"))
                } else {
                    ItemRef(idOf("ep-$it"), "Episode", seriesId = idOf("hidden-${it % 50_000}"))
                }
            }
        // warm up
        refs.take(10_000).forEach { index[it.id] ?: index[it.seriesId] }
        val start = System.nanoTime()
        var hidden = 0
        refs.forEach { ref ->
            if ((index[ref.id] ?: index[ref.seriesId]) != null) {
                hidden++
            } else if (ref.tags != null && policy.matchesAnyRule(ref.tags!!)) {
                hidden++
            }
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        assertEquals(100_000, hidden)
        // 200k checks against 50k hidden ids and 5,000 tags: generous bound for slow CI machines
        assertTrue("200k checks took $millis ms", millis < 2_000)
    }
}
