package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.idOf
import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.uuidOf
import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultDefinition
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultLibrary
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.PagedQueryInfo
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.VisiblePaging
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads pages the way Wholphin's RequestPager does: position `p` lives on page `p / pageSize`,
 * the list size is the total of the first page, and pages are cached by number.
 */
private class PagerSimulator(
    private val paging: VisiblePaging<GetItemsRequest>,
    private val pageSize: Int,
) {
    var size = -1
    private val pages = mutableMapOf<Int, List<BaseItemDto>>()

    suspend fun init(position: Int = 0) {
        val page = paging.fetchPage(position / pageSize, pageSize, true)!!
        size = page.totalCount!!
        pages[position / pageSize] = page.items
    }

    suspend fun get(position: Int): BaseItemDto? {
        require(position in 0 until size)
        val number = position / pageSize
        val page = pages.getOrPut(number) { paging.fetchPage(number, pageSize, false)!!.items }
        return page.getOrNull(position % pageSize)
    }

    suspend fun all(): List<BaseItemDto?> = (0 until size).map { get(it) }
}

class PagingTest {
    private val animeOnly =
        HiddenVaultConfig(
            vaults =
                listOf(
                    VaultDefinition("anime", "Anime", listOf(VaultLibrary(lib("lib-anime"), "Anime", "tvshows", listOf("ecchi")))),
                ),
        )

    /** 60 series s00..s59 in Anime, every third one tagged "ecchi" */
    private fun FakeJellyfin.bigLibrary(count: Int = 60) {
        library("lib-anime", "tvshows")
        library("lib-docs", "movies")
        for (i in 0 until count) {
            series("lib-anime", "s%02d".format(i), if (i % 3 == 0) "ecchi" else "Action")
        }
        for (i in 0 until 5) movie("lib-docs", "d$i")
    }

    private fun test(
        catalog: FakeJellyfin.() -> Unit = { bigLibrary() },
        block: suspend TestScope.(Device, FakeJellyfin) -> Unit,
    ) = runTest {
        val server = FakeJellyfin().apply(catalog)
        val device = Device(server, backgroundScope)
        device.configure(animeOnly)
        server.reset()
        block(device, server)
    }

    private fun Device.paging(
        request: GetItemsRequest,
        info: PagedQueryInfo =
            PagedQueryInfo(
                anchorId =
                    request.parentId?.let {
                        com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
                            .of(it)
                    },
            ),
    ) = VisiblePaging(
        api,
        info,
        { start, limit, total -> request.copy(startIndex = start, limit = limit, enableTotalRecordCount = total) },
        { client, req -> client.itemsApi.getItems(req).content },
    )

    private val animeGrid =
        GetItemsRequest(parentId = uuidOf("lib-anime"), recursive = true, includeItemTypes = listOf(BaseItemKind.SERIES))

    private val visibleOrder = (0 until 60).filter { it % 3 != 0 }.map { "s%02d".format(it) }

    @Test
    fun `visible positions page through without repeats, gaps or hidden items`() =
        test { device, server ->
            val paging = device.paging(animeGrid)
            val pager = PagerSimulator(paging, pageSize = 10)
            pager.init()
            assertEquals(40, pager.size)
            val all = names(pager.all().map { it!! })
            assertEquals(visibleOrder, all)
            assertEquals(all.size, all.toSet().size)
            // first window + skeleton + one request per further page
            assertEquals(2 + 3, paging.requestCount)
            assertEquals(paging.requestCount, server.calls.size)
        }

    @Test
    fun `jumping straight to a later position is exact`() =
        test { device, _ ->
            val paging = device.paging(animeGrid)
            val pager = PagerSimulator(paging, pageSize = 10)
            pager.init(position = 35)
            assertEquals(40, pager.size)
            assertEquals(visibleOrder[35], names(listOf(pager.get(35)!!)).single())
            assertEquals(2, paging.requestCount)
            assertEquals(visibleOrder[12], names(listOf(pager.get(12)!!)).single())
            assertEquals(3, paging.requestCount)
        }

    @Test
    fun `a list that fits into one request costs exactly one`() =
        test { device, _ ->
            val paging = device.paging(animeGrid)
            val page = paging.fetchPage(0, 100, true)!!
            assertEquals(visibleOrder, names(page.items))
            assertEquals(40, page.totalCount)
            assertEquals(1, paging.requestCount)
        }

    @Test
    fun `a library without rules is left to the normal pager`() =
        test { device, server ->
            val docs = GetItemsRequest(parentId = uuidOf("lib-docs"), recursive = true)
            assertNull(device.paging(docs).fetchPage(0, 10, true))
            assertEquals(0, server.calls.size)
        }

    @Test
    fun `items hidden after the skeleton was read never show and nothing repeats`() =
        test { device, server ->
            val paging = device.paging(animeGrid)
            val pager = PagerSimulator(paging, pageSize = 10)
            pager.init()
            // s01 gets tagged on the server and the index learns it
            server.items.remove("s01")
            server.series("lib-anime", "s01", "Ecchi")
            device.service.refreshIndex()
            val seen = mutableListOf<String>()
            for (p in 10 until pager.size) pager.get(p)?.let { seen += names(listOf(it)) }
            assertTrue(seen.none { it == "s01" || it.removePrefix("s").toInt() % 3 == 0 })
            assertEquals(seen.size, seen.toSet().size)
        }

    @Test
    fun `random order fills the page without positions`() =
        test { device, _ ->
            val random = animeGrid.copy(sortBy = listOf(ItemSortBy.RANDOM))
            val paging = device.paging(random, PagedQueryInfo(anchorId = idOf("lib-anime"), unstableOrder = true))
            val page = paging.fetchPage(0, 10, true)!!
            assertEquals(10, page.items.size)
            assertTrue(names(page.items).none { it.removePrefix("s").toInt() % 3 == 0 })
            assertEquals(1, paging.requestCount)
        }

    @Test
    fun `inside an open vault its own children page normally`() =
        test({
            seedStandard()
            season("lib-anime", "a1-s2", "a1")
        }) { device, _ ->
            device.configure(standardConfig())
            device.enter("anime")
            val seasons = GetItemsRequest(parentId = uuidOf("a1"), includeItemTypes = listOf(BaseItemKind.SEASON))
            val page = device.paging(seasons).fetchPage(0, 10, true)
            assertNotNull(page)
            assertEquals(listOf("a1-s1", "a1-s2"), names(page!!.items))
        }

    @Test
    fun `episodes of a hidden season page exactly through the episodes endpoint`() =
        test({
            library("lib-anime", "tvshows")
            series("lib-anime", "long", "Action")
            add(FakeJellyfin.Item("long-s1", "lib-anime", "Season", seriesName = "long", parentName = "long"))
            add(FakeJellyfin.Item("long-s2", "lib-anime", "Season", listOf("Ecchi"), seriesName = "long", parentName = "long"))
            for (i in 0 until 250) episode("lib-anime", "long-e%03d".format(i), "long", season = if (i % 2 == 0) "long-s1" else "long-s2")
        }) { device, _ ->
            val request =
                org.jellyfin.sdk.model.api.request
                    .GetEpisodesRequest(seriesId = uuidOf("long"))
            val paging =
                VisiblePaging(
                    device.api,
                    PagedQueryInfo(anchorId = idOf("long")),
                    { start, limit, _ -> request.copy(startIndex = start, limit = limit) },
                    { client, req -> client.tvShowsApi.getEpisodes(req).content },
                )
            val pageSize = 100
            val first = paging.fetchPage(0, pageSize, true)!!
            assertEquals(125, first.totalCount)
            val all = first.items + paging.fetchPage(1, pageSize, false)!!.items
            val names = names(all)
            assertEquals((0 until 250 step 2).map { "long-e%03d".format(it) }, names)
        }

    // ---------------------------------------------------------------------------------------
    // Direct lists (home rows and other top-N calls) through the decorator
    // ---------------------------------------------------------------------------------------

    @Test
    fun `rows stay full - a short list reads ahead within a budget`() =
        test { device, server ->
            val row =
                device.api.itemsApi
                    .getItems(animeGrid.copy(limit = 15))
                    .content
            assertEquals(visibleOrder.take(15), names(row))
            assertTrue(server.calls.size <= 2)
        }

    @Test
    fun `read ahead stops at its budget`() =
        test({
            library("lib-anime", "tvshows")
            for (i in 0 until 2000) series("lib-anime", "h$i", "ecchi")
            series("lib-anime", "visible")
        }) { device, server ->
            val row =
                device.api.itemsApi
                    .getItems(animeGrid.copy(limit = 15))
                    .content
            assertTrue(row.items.isEmpty())
            assertEquals(1 + 3, server.calls.size)
        }

    @Test
    fun `nothing hidden costs no extra request`() =
        test { device, server ->
            device.api.itemsApi.getItems(GetItemsRequest(parentId = uuidOf("lib-docs"), recursive = true, startIndex = 2, limit = 2))
            assertEquals(1, server.calls.size)
        }

    @Test
    fun `letter jumps count without the hidden items`() =
        test { device, server ->
            val count =
                device.api.itemsApi
                    .getItems(animeGrid.copy(nameLessThan = "s30", limit = 0, enableTotalRecordCount = true))
                    .content
            // s00..s29 = 30 items, ten of them hidden
            assertEquals(20, count.totalRecordCount)
            assertEquals(2, server.calls.size)
        }
}
