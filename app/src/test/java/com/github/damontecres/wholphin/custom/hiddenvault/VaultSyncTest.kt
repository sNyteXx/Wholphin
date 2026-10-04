package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.FakeJellyfin.Companion.idOf
import com.github.damontecres.wholphin.custom.hiddenvault.data.DisplayPreferencesVaultRemote
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultDeviceSettings
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config travels through the user's own Jellyfin display preferences.
 */
class VaultSyncTest {
    private class Prefs(
        val server: FakeJellyfin,
    ) {
        var gets = 0
        var saves = 0
        var hold: CompletableDeferred<Unit>? = null

        val remote =
            DisplayPreferencesVaultRemote(
                read = {
                    gets++
                    hold?.await()
                    server.displayPreferencesApi
                        .getDisplayPreferences(
                            DisplayPreferencesVaultRemote.PREFERENCES_ID,
                            client = DisplayPreferencesVaultRemote.CLIENT,
                        ).content
                },
                write = {
                    server.displayPreferencesApi.updateDisplayPreferences(
                        DisplayPreferencesVaultRemote.PREFERENCES_ID,
                        client = DisplayPreferencesVaultRemote.CLIENT,
                        data = it,
                    )
                    saves++
                },
            )
    }

    private fun test(block: suspend TestScope.(FakeJellyfin, Prefs) -> Unit) =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            block(server, Prefs(server))
        }

    @Test
    fun `a config saved on the TV hides the same content on the phone`() =
        test { server, prefs ->
            val tv = Device(server, backgroundScope, prefs.remote)
            tv.configure()
            testScheduler.runCurrent()
            assertEquals(1, prefs.saves)
            val phone = Device(server, backgroundScope, prefs.remote, now = tv.now + 3_600_000)
            assertFalse(phone.service.isActive)
            phone.service.ensureReady()
            assertTrue(phone.service.isActive)
            assertEquals(tv.service.fingerprint, phone.service.fingerprint)
            assertTrue(phone.service.index!!.contains(idOf("a1")))
        }

    @Test
    fun `only the rules travel - no PIN, no index, no unlock state`() =
        test { server, prefs ->
            val tv = Device(server, backgroundScope, prefs.remote)
            tv.vault.pins.set(SCOPE, "1234")
            tv.enter("anime")
            tv.configure()
            testScheduler.runCurrent()
            val stored =
                server.displayPreferences["${DisplayPreferencesVaultRemote.CLIENT}/${DisplayPreferencesVaultRemote.PREFERENCES_ID}"]!!
            assertEquals(setOf(DisplayPreferencesVaultRemote.KEY), stored.customPrefs.keys)
            val payload = stored.customPrefs[DisplayPreferencesVaultRemote.KEY]!!
            assertEquals(setOf("version", "updatedAt", "vaults", "settings"), Json.parseToJsonElement(payload).jsonObject.keys)
            assertFalse(payload.contains("pin", ignoreCase = true))
            assertFalse(payload.contains("unlock", ignoreCase = true))
            assertFalse(payload.contains("index", ignoreCase = true))
        }

    @Test
    fun `the newer copy wins in both directions`() =
        test { server, prefs ->
            val tv = Device(server, backgroundScope, prefs.remote)
            tv.configure()
            testScheduler.runCurrent()
            val phone = Device(server, backgroundScope, prefs.remote, now = tv.now + 3_600_000)
            phone.service.ensureReady()
            val narrowed =
                standardConfig().copy(
                    vaults =
                        listOf(
                            standardConfig().vaults[0].copy(
                                libraries = listOf(VaultLibrary(lib("lib-anime"), "Anime", "tvshows", listOf("Action"))),
                            ),
                        ),
                )
            phone.configure(narrowed)
            testScheduler.runCurrent()
            tv.now = phone.now + 3_600_000
            tv.start()
            tv.service.ensureReady()
            assertTrue(tv.service.index!!.contains(idOf("a5")))
            assertFalse(tv.service.index!!.contains(idOf("a1")))
            assertFalse(phone.service.syncNow())
        }

    @Test
    fun `a save made offline reaches the server on the next sync`() =
        test { server, prefs ->
            val tv = Device(server, backgroundScope, prefs.remote)
            server.offline = true
            tv.configure()
            testScheduler.runCurrent()
            assertEquals(0, prefs.saves)
            server.offline = false
            tv.service.syncNow()
            assertEquals(1, prefs.saves)
            val phone = Device(server, backgroundScope, prefs.remote, now = tv.now + 1)
            phone.service.ensureReady()
            assertTrue(phone.service.isActive)
        }

    @Test
    fun `sync off - nothing read, nothing written`() =
        test { server, prefs ->
            Device(server, backgroundScope, prefs.remote).apply {
                configure()
                testScheduler.runCurrent()
            }
            val phone = Device(server, backgroundScope, prefs.remote)
            phone.service.saveDeviceSettings(VaultDeviceSettings(syncEnabled = false))
            phone.start()
            prefs.gets = 0
            phone.service.ensureReady()
            assertEquals(0, prefs.gets)
            assertFalse(phone.service.isActive)
            // turning it back on pulls at once
            phone.service.saveDeviceSettings(VaultDeviceSettings(syncEnabled = true))
            assertTrue(phone.service.isActive)
        }

    @Test
    fun `a slow server holds the first lists back only briefly`() =
        test { server, prefs ->
            Device(server, backgroundScope, prefs.remote).apply {
                configure()
                testScheduler.runCurrent()
            }
            val hold = CompletableDeferred<Unit>()
            prefs.hold = hold
            val phone = Device(server, backgroundScope, prefs.remote)
            val started = testScheduler.currentTime
            phone.service.ensureReady()
            assertTrue(testScheduler.currentTime - started <= HiddenContentService.SYNC_WAIT_MS)
            assertFalse(phone.service.isActive)
            hold.complete(Unit)
            testScheduler.runCurrent()
            assertTrue(phone.service.isActive)
        }

    @Test
    fun `own namespace on the server`() {
        assertEquals("wholphin-hidden-vault", DisplayPreferencesVaultRemote.PREFERENCES_ID)
        assertTrue(DisplayPreferencesVaultRemote.CLIENT != "Wholphin" && DisplayPreferencesVaultRemote.CLIENT != "emby")
    }
}
