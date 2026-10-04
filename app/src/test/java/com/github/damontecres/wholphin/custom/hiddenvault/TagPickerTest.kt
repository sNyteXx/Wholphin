package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.uuidOf
import com.github.damontecres.wholphin.custom.hiddenvault.model.TagPickerModel
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagPickerTest {
    private val serverTags = (0 until 600).map { "Genre %03d".format(it) }

    @Test
    fun `ticking a tag never moves a row`() {
        var selected = listOf("ecchi")
        var extras = TagPickerModel.extras(selected, serverTags, emptyList())
        val before = TagPickerModel.rows(serverTags, extras, "")
        selected = TagPickerModel.toggle(selected, "Genre 003")
        extras = TagPickerModel.extras(selected, serverTags, extras)
        assertEquals(before, TagPickerModel.rows(serverTags, extras, ""))
        assertEquals(listOf("ecchi", "Genre 003"), selected)
        // unticking the hand typed tag keeps its row too
        selected = TagPickerModel.toggle(selected, "ECCHI")
        extras = TagPickerModel.extras(selected, serverTags, extras)
        assertEquals(before, TagPickerModel.rows(serverTags, extras, ""))
        assertEquals(listOf("Genre 003"), selected)
    }

    @Test
    fun `a tag never shows twice, even when it was typed before the server list arrived`() {
        // extras computed while the server's tags were still loading
        val stale = TagPickerModel.extras(listOf("Anime"), emptyList(), emptyList())
        val rows = TagPickerModel.rows(listOf("anime", "Kids", "Private"), stale, "")
        assertEquals(rows.size, rows.map { it.lowercase() }.toSet().size)
        assertEquals(3, rows.size)
    }

    @Test
    fun `the filter narrows case insensitively`() {
        val rows = TagPickerModel.rows(serverTags, emptyList(), "genre 12")
        assertEquals((120 until 130).map { "Genre %03d".format(it) }, rows)
        assertEquals(600, TagPickerModel.rows(serverTags, emptyList(), "  ").size)
    }

    @Test
    fun `adding by hand ignores a tag already chosen in another spelling`() {
        assertEquals(listOf("Ecchi"), TagPickerModel.add(listOf("Ecchi"), "  ecchi "))
        assertEquals(listOf("Ecchi", "Private"), TagPickerModel.add(listOf("Ecchi"), " Private "))
        assertEquals(listOf("Ecchi"), TagPickerModel.add(listOf("Ecchi"), "   "))
    }

    @Test
    fun `a few hundred tags are filtered instantly`() {
        val many = (0 until 5_000).map { "Tag $it" }
        val start = System.nanoTime()
        repeat(100) { TagPickerModel.rows(many, emptyList(), "tag 12") }
        val millis = (System.nanoTime() - start) / 1_000_000
        assertTrue("100 filters took $millis ms", millis < 2_000)
    }
}

/**
 * The detail gate's quick answer, used to decide without waiting whether a page may draw
 */
class DetailGateTest {
    @Test
    fun `known items are decided without waiting`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val device = Device(server, backgroundScope)
            device.configure()
            // indexed: refused at once
            assertEquals(false, device.api.quickAccess(uuidOf("a1")))
            // seen in a list before: decided from what was seen
            device.api.itemsApi.getItems(GetItemsRequest(parentId = uuidOf("lib-anime"), recursive = true))
            assertEquals(true, device.api.quickAccess(uuidOf("a5e1")))
            assertEquals(false, device.api.quickAccess(uuidOf("a2e1")))
            // never seen: needs the slow path
            assertNull(device.api.quickAccess(uuidOf("s2e1")))
            assertTrue(device.api.refusesPlayback(uuidOf("s2e1")))
            // inside its open vault it opens
            device.enter("anime")
            assertEquals(true, device.api.quickAccess(uuidOf("a1")))
            assertEquals(false, device.api.quickAccess(uuidOf("s2")))
        }

    @Test
    fun `without rules nothing waits`() =
        runTest {
            val device = Device(FakeJellyfin().apply { seedStandard() }, backgroundScope)
            assertEquals(true, device.api.quickAccess(uuidOf("a1")))
            assertFalse(device.api.refusesPlayback(uuidOf("a1")))
        }
}
