package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.uuidOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The normal app never sees hidden content, on any list the app reads.
 */
class NormalContextTest {
    private val hidden = listOf("a1", "a2", "a1e1", "a2e1", "m1", "m2", "s2", "s2e1", "f2")

    private fun test(block: suspend TestScope.(Device, FakeJellyfin) -> Unit) =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            server.reset()
            block(device, server)
        }

    private fun assertNoneHidden(names: List<String>) {
        val leaked = names.filter { it in hidden }
        assertTrue("hidden items leaked: $leaked", leaked.isEmpty())
    }

    @Test
    fun `recently added`() =
        test { device, _ ->
            val latest =
                device.api.userLibraryApi
                    .getLatestMedia(GetLatestMediaRequest(parentId = uuidOf("lib-anime"), limit = 15))
                    .content
            assertEquals(listOf("a6", "a5", "a4", "a3"), names(latest))
        }

    @Test
    fun `continue watching`() =
        test { device, _ ->
            val resume =
                device.api.itemsApi
                    .getResumeItems(GetResumeItemsRequest(limit = 15))
                    .content
            assertEquals(listOf("a5e2"), names(resume))
        }

    @Test
    fun `next up hides episodes of hidden series through the series`() =
        test { device, _ ->
            val nextUp =
                device.api.tvShowsApi
                    .getNextUp(GetNextUpRequest(limit = 15))
                    .content
            assertEquals(listOf("a5e1", "s1e1"), names(nextUp))
        }

    @Test
    fun `library grid`() =
        test { device, _ ->
            val grid =
                device.api.itemsApi
                    .getItems(
                        GetItemsRequest(
                            parentId = uuidOf("lib-anime"),
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                            recursive = true,
                            limit = 48,
                        ),
                    ).content
            assertEquals(listOf("a3", "a4", "a5", "a6"), names(grid))
            assertEquals(4, grid.totalRecordCount)
        }

    @Test
    fun `search over titles and episodes`() =
        test { device, _ ->
            val result =
                device.api.itemsApi
                    .getItems(GetItemsRequest(searchTerm = "a", recursive = true, limit = 50))
                    .content
            assertNoneHidden(names(result))
            assertTrue(names(result).containsAll(listOf("a3", "a5", "a5e1")))
        }

    @Test
    fun `favorites`() =
        test { device, server ->
            listOf("a1", "a1e1", "a5", "m1").forEach { server.items[it]!!.favorite = true }
            val favorites =
                device.api.itemsApi
                    .getItems(GetItemsRequest(isFavorite = true, recursive = true))
                    .content
            assertEquals(listOf("a5"), names(favorites))
        }

    @Test
    fun `genre and person lists`() =
        test { device, server ->
            server.add(FakeJellyfin.Item("g1", "lib-anime", "Series", listOf("ecchi"), genres = listOf("Comedy"), people = listOf("p")))
            server.add(FakeJellyfin.Item("g2", "lib-anime", "Series", listOf("Comedy"), genres = listOf("Comedy"), people = listOf("p")))
            device.service.refreshIndex()
            val byGenre =
                device.api.itemsApi
                    .getItems(GetItemsRequest(genres = listOf("Comedy"), recursive = true))
                    .content
            assertEquals(listOf("g2"), names(byGenre))
            val byPerson =
                device.api.itemsApi
                    .getItems(GetItemsRequest(personIds = listOf(uuidOf("p")), recursive = true))
                    .content
            assertEquals(listOf("g2"), names(byPerson))
        }

    @Test
    fun `similar items`() =
        test { device, _ ->
            val similar =
                device.api.libraryApi
                    .getSimilarItems(uuidOf("a5"), limit = 10)
                    .content
            assertNoneHidden(names(similar))
            assertTrue(names(similar).contains("a3"))
        }

    @Test
    fun `an anime tag outside the anime vault stays visible`() =
        test { device, _ ->
            val shows =
                device.api.itemsApi
                    .getItems(
                        GetItemsRequest(
                            parentId = uuidOf("lib-shows"),
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                            recursive = true,
                            limit = 48,
                        ),
                    ).content
            // s1 carries "ecchi", which is no rule of the shows library; "hidden gem" is not "hidden"
            assertEquals(listOf("s1", "s3"), names(shows))
        }

    @Test
    fun `adult does not hide adult animation`() =
        test { device, _ ->
            val films =
                device.api.itemsApi
                    .getItems(GetItemsRequest(parentId = uuidOf("lib-movies"), recursive = true, limit = 48))
                    .content
            assertEquals(listOf("f1", "f3"), names(films))
        }

    @Test
    fun `collections hide on their own tags only`() =
        test { device, server ->
            server.boxSet("box1", "ecchi")
            server.boxSet("box2", "Classics")
            val boxes =
                device.api.itemsApi
                    .getItems(GetItemsRequest(parentId = uuidOf("lib-boxsets"), recursive = true, limit = 48))
                    .content
            assertEquals(listOf("box2"), names(boxes))
        }

    @Test
    fun `hidden tags are not offered as filters`() =
        test { device, _ ->
            val anime =
                device.api.filterApi
                    .getQueryFiltersLegacy(parentId = uuidOf("lib-anime"))
                    .content
            assertFalse(anime.tags!!.contains("Ecchi"))
            assertFalse(anime.tags!!.contains("ECCHI"))
            assertTrue(anime.tags!!.contains("ecchi comedy"))
            val shows =
                device.api.filterApi
                    .getQueryFiltersLegacy(parentId = uuidOf("lib-shows"))
                    .content
            assertTrue(shows.tags!!.contains("ecchi"))
            assertFalse(shows.tags!!.contains("private"))
        }

    @Test
    fun `opening a hidden item is refused like a missing one`() =
        test { device, server ->
            for (name in listOf("a1", "a1e1", "a1-s1", "f2")) {
                try {
                    device.api.userLibraryApi.getItem(uuidOf(name))
                    fail("$name opened")
                } catch (ex: InvalidStatusException) {
                    assertEquals(404, ex.status)
                }
            }
            // Indexed items are refused without even asking the server
            assertEquals(0, server.calls.count { it.path == "/Items/{itemId}" && FakeJellyfin.nameOf(it.str("path:itemId")!!) == "a1" })
            assertEquals(
                "a5",
                FakeJellyfin.nameOf(
                    device.api.userLibraryApi
                        .getItem(uuidOf("a5"))
                        .content.id
                        .toString(),
                ),
            )
        }

    @Test
    fun `episodes and seasons of a hidden series are empty`() =
        test { device, _ ->
            assertTrue(
                device.api.tvShowsApi
                    .getEpisodes(uuidOf("a1"))
                    .content.items
                    .isEmpty(),
            )
            assertTrue(
                device.api.tvShowsApi
                    .getSeasons(uuidOf("a1"))
                    .content.items
                    .isEmpty(),
            )
            val seasons =
                device.api.itemsApi
                    .getItems(GetItemsRequest(parentId = uuidOf("a1"), includeItemTypes = listOf(BaseItemKind.SEASON)))
                    .content
            assertTrue(seasons.items.isEmpty())
        }

    @Test
    fun `tags ride along with the normal request instead of a separate one`() =
        test { device, server ->
            device.api.itemsApi.getItems(
                GetItemsRequest(parentId = uuidOf("lib-anime"), limit = 10, fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES)),
            )
            assertEquals(listOf("Overview", "Genres", "Tags"), server.calls.last().list("fields"))
            assertEquals(1, server.calls.size)
        }

    @Test
    fun `without rules every call passes straight through`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            val latest =
                device.api.userLibraryApi
                    .getLatestMedia(GetLatestMediaRequest(parentId = uuidOf("lib-anime"), limit = 15))
                    .content
            assertEquals(6, latest.size)
            assertEquals(1, server.calls.size)
            assertEquals(null, server.calls.single().list("fields"))
        }

    @Test
    fun `endpoints outside the whitelist are never touched`() =
        test { device, server ->
            device.api.userViewsApi.getUserViews()
            assertEquals(1, server.calls.size)
        }

    @Test
    fun `changing tags swaps what is hidden`() =
        test { device, _ ->
            device.configure(
                standardConfig().copy(
                    vaults =
                        listOf(
                            standardConfig().vaults[0].copy(
                                libraries = listOf(standardConfig().vaults[0].libraries[0].copy(tags = listOf("Action"))),
                            ),
                        ),
                ),
            )
            val latest =
                device.api.userLibraryApi
                    .getLatestMedia(GetLatestMediaRequest(parentId = uuidOf("lib-anime"), limit = 15))
                    .content
            assertTrue(names(latest).contains("a1"))
            assertFalse(names(latest).contains("a5"))
        }
}
