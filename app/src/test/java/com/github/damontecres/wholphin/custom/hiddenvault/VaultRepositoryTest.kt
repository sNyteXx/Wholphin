package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.idOf
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultRepository
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vault's own screens read only that vault's content, straight from the server by tag.
 */
class VaultRepositoryTest {
    private fun test(block: suspend TestScope.(Device, FakeJellyfin, (String) -> VaultRepository) -> Unit) =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            server.reset()
            block(device, server) { id -> VaultRepository(device.service, device.service.config.vault(id)!!, server) }
        }

    @Test
    fun `library grids ask the server for that library's tags`() =
        test { _, server, repo ->
            val anime = repo("anime")
            val page = anime.libraryPage(anime.vault.library(lib("lib-anime"))!!)
            assertEquals(listOf("a1", "a2"), names(page.items))
            val call = server.calls.single()
            assertEquals(idOf("lib-anime"), ItemIds.normalize(call.str("parentId")))
            assertEquals(listOf("ecchi"), call.list("tags"))
            // "Ecchi!" came back from the server's loose match and was dropped
            assertEquals(3, page.rawCount)
        }

    @Test
    fun `anime vault shows only anime hidden content`() =
        test { _, _, repo ->
            val anime = repo("anime")
            assertEquals(listOf("m1", "m2"), names(anime.libraryPage(anime.vault.library(lib("lib-anime-movies"))!!).items))
            assertEquals(setOf("a2e1", "m1"), names(anime.continueWatching()).toSet())
            assertEquals(listOf("a1e1"), names(anime.nextUp()))
            val search = names(anime.search("a"))
            assertTrue(search.containsAll(listOf("a1", "a2", "a1e1", "a2e1")))
            assertTrue(search.none { it in listOf("a3", "a5", "a5e1", "s1", "s2", "s2e1", "f2") })
        }

    @Test
    fun `shows vault shows its own content and nothing of the anime vault`() =
        test { _, _, repo ->
            val shows = repo("shows")
            assertEquals(listOf("s2"), names(shows.libraryPage(shows.vault.library(lib("lib-shows"))!!).items))
            assertEquals(listOf("f2"), names(shows.libraryPage(shows.vault.library(lib("lib-movies"))!!).items))
            assertEquals(listOf("s2e1"), names(shows.nextUp()))
            assertTrue(shows.continueWatching().isEmpty())
            val search = names(shows.search("s"))
            assertTrue(search.containsAll(listOf("s2", "s2e1")))
            assertFalse(search.contains("s1"))
            assertFalse(search.contains("s3"))
        }

    @Test
    fun `hide watched asks the server for unplayed titles only`() =
        test { device, server, repo ->
            server.items["a1"]!!.played = true
            device.configure(
                device.service.config.copy(
                    settings =
                        device.service.config.settings
                            .copy(hideWatched = true),
                ),
            )
            server.reset()
            val anime = repo("anime")
            assertEquals(listOf("a2"), names(anime.libraryPage(anime.vault.library(lib("lib-anime"))!!).items))
            assertEquals(listOf("IsUnplayed"), server.calls.single().list("filters"))
            assertFalse(names(anime.recentlyAdded()).contains("a1"))
            assertFalse(names(anime.search("a")).contains("a1"))
            // next up is unwatched by nature
            assertEquals(listOf("a1e1"), names(anime.nextUp()))
        }

    @Test
    fun `watched titles stay by default`() =
        test { _, server, repo ->
            server.items["a1"]!!.played = true
            val anime = repo("anime")
            assertTrue(names(anime.libraryPage(anime.vault.library(lib("lib-anime"))!!).items).contains("a1"))
            assertEquals(null, server.calls.last().list("filters"))
        }

    @Test
    fun `recently added merges the libraries newest first`() =
        test { _, _, repo ->
            val recent = names(repo("anime").recentlyAdded())
            assertTrue(recent.containsAll(listOf("a1", "a2", "m1", "m2")))
            assertTrue(recent.indexOf("a2") < recent.indexOf("a1"))
        }

    @Test
    fun `rows are loaded once per visit`() =
        test { _, server, repo ->
            val anime = repo("anime")
            val library = anime.vault.library(lib("lib-anime"))!!
            anime.libraryRow(library)
            anime.libraryRow(library)
            anime.nextUp()
            anime.nextUp()
            assertEquals(1, server.count("/Items"))
            assertEquals(1, server.count("/Shows/NextUp"))
            anime.clear()
            anime.nextUp()
            assertEquals(2, server.count("/Shows/NextUp"))
        }
}
