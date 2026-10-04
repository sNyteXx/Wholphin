package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.data.MemoryVaultStore
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultSettings
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultLockEvent
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultLockReason
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultPinStore
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultSessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSessionTest {
    private var now = 1_790_000_000_000L
    private var settings = VaultSettings()

    private fun session() = VaultSessionManager(clock = { now }, settingsFor = { settings })

    @Test
    fun `starts locked and nothing survives an app restart`() {
        val session = session()
        assertFalse(session.isUnlocked(SCOPE, "anime"))
        session.unlock(SCOPE, "anime")
        assertTrue(session.isUnlocked(SCOPE, "anime"))
        assertFalse(session().isUnlocked(SCOPE, "anime"))
    }

    @Test
    fun `unlocked is not entered`() {
        val session = session()
        session.unlock(SCOPE, "anime")
        assertTrue(session.enteredVaults(SCOPE).isEmpty())
        session.enter(SCOPE, "anime")
        assertEquals(setOf("anime"), session.enteredVaults(SCOPE))
        assertTrue(session.enteredVaults(OTHER_USER).isEmpty())
    }

    @Test
    fun `vaults unlock separately`() {
        val session = session()
        session.unlock(SCOPE, "anime")
        assertFalse(session.isUnlocked(SCOPE, "shows"))
    }

    @Test
    fun `leaving the vault locks it by default`() =
        runTest {
            val session = session()
            val events = mutableListOf<VaultLockEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.lockEvents.collect { events += it } }
            session.unlock(SCOPE, "anime")
            // vault home and its library grid
            session.enter(SCOPE, "anime")
            session.enter(SCOPE, "anime")
            session.leave(SCOPE, "anime")
            assertTrue(session.isUnlocked(SCOPE, "anime"))
            session.leave(SCOPE, "anime")
            assertFalse(session.isUnlocked(SCOPE, "anime"))
            assertEquals(VaultLockReason.LEFT_VAULT, events.single().reason)
        }

    @Test
    fun `with lock on leave off the timeout still applies`() {
        settings = VaultSettings(autoLockMinutes = 5, lockOnLeave = false)
        val session = session()
        session.unlock(SCOPE, "anime")
        session.enter(SCOPE, "anime")
        session.leave(SCOPE, "anime")
        assertTrue(session.isUnlocked(SCOPE, "anime"))
        assertTrue(session.enteredVaults(SCOPE).isEmpty())
        now += 4 * 60_000
        session.checkTimeouts()
        assertTrue(session.isUnlocked(SCOPE, "anime"))
        now += 2 * 60_000
        session.checkTimeouts()
        assertFalse(session.isUnlocked(SCOPE, "anime"))
    }

    @Test
    fun `activity keeps it open, inactivity locks it after the default 15 minutes`() {
        val session = session()
        session.unlock(SCOPE, "anime")
        session.enter(SCOPE, "anime")
        repeat(6) {
            now += 10 * 60_000
            session.touch(SCOPE, "anime")
            session.checkTimeouts()
        }
        assertTrue(session.isUnlocked(SCOPE, "anime"))
        now += 14 * 60_000
        session.checkTimeouts()
        assertTrue(session.isUnlocked(SCOPE, "anime"))
        now += 60_000
        session.checkTimeouts()
        assertFalse(session.isUnlocked(SCOPE, "anime"))
    }

    @Test
    fun `user switch, server switch and sign out lock`() {
        val session = session()
        session.unlock(SCOPE, "anime")
        session.unlock(SCOPE, "shows")
        session.onActiveScopeChanged(SCOPE)
        assertTrue(session.isUnlocked(SCOPE, "anime"))
        session.onActiveScopeChanged(OTHER_USER)
        assertFalse(session.hasUnlocked)
        session.unlock(SCOPE, "anime")
        session.onActiveScopeChanged(OTHER_SERVER)
        assertFalse(session.hasUnlocked)
        session.unlock(SCOPE, "anime")
        session.openPrivateArea(SCOPE)
        session.onActiveScopeChanged(null)
        assertFalse(session.hasUnlocked)
        assertFalse(session.isPrivateAreaOpen(SCOPE))
    }

    @Test
    fun `switching the active account in the vault locks everything`() =
        runTest {
            val device = Device(FakeJellyfin().apply { seedStandard() }, backgroundScope)
            device.enter("anime")
            device.session.openPrivateArea(SCOPE)
            device.vault.setActiveScope(OTHER_USER)
            assertFalse(device.session.hasUnlocked)
            assertTrue(device.vault.enteredVaults().isEmpty())
        }

    @Test
    fun `the first request after a user switch already uses the new user's rules`() =
        runTest {
            val server = FakeJellyfin().apply { seedStandard() }
            val store =
                com.github.damontecres.wholphin.custom.hiddenvault.data
                    .MemoryVaultStore()
            var signedIn: com.github.damontecres.wholphin.custom.hiddenvault.data.VaultScope? = SCOPE
            val session = session()
            val vault =
                HiddenVault(
                    serviceFactory = { scope ->
                        com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService(
                            scope,
                            store,
                            com.github.damontecres.wholphin.custom.hiddenvault.data
                                .JellyfinIndexSource(server),
                            null,
                            backgroundScope,
                            { now },
                        )
                    },
                    session = session,
                    pins = VaultPinStore(store, { now }, iterations = 50),
                    appScope = backgroundScope,
                    scopeSource = { signedIn },
                )
            // user-1 has rules, user-2 has none
            vault.setActiveScope(SCOPE)
            vault.currentService()!!.saveConfig(standardConfig())
            session.unlock(SCOPE, "anime")
            signedIn = OTHER_USER
            assertEquals(OTHER_USER, vault.currentService()!!.scope)
            assertFalse(session.hasUnlocked)
            signedIn = SCOPE
            assertTrue(vault.activeService()!!.isActive)
        }

    @Test
    fun `every lock is announced once`() =
        runTest {
            val session = session()
            val events = mutableListOf<VaultLockEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.lockEvents.collect { events += it } }
            session.unlock(SCOPE, "anime")
            session.lock(SCOPE, "anime", VaultLockReason.MANUAL)
            session.lock(SCOPE, "anime", VaultLockReason.MANUAL)
            assertEquals(1, events.size)
            assertEquals("anime", events.single().vaultId)
        }

    @Test
    fun `the private area times out like a vault`() {
        val session = session()
        session.openPrivateArea(SCOPE)
        assertTrue(session.isPrivateAreaOpen(SCOPE))
        assertFalse(session.isPrivateAreaOpen(OTHER_USER))
        now += 14 * 60_000
        session.touchPrivateArea()
        now += 14 * 60_000
        session.checkTimeouts()
        assertTrue(session.isPrivateAreaOpen(SCOPE))
        now += 16 * 60_000
        session.checkTimeouts()
        assertFalse(session.isPrivateAreaOpen(SCOPE))
    }

    @Test
    fun `a removed vault can't stay open`() =
        runTest {
            val device = Device(FakeJellyfin().apply { seedStandard() }, backgroundScope)
            device.configure()
            device.enter("shows")
            device.configure(standardConfig().copy(vaults = standardConfig().vaults.take(1)))
            device.vault.onConfigSaved()
            assertFalse(device.session.isUnlocked(SCOPE, "shows"))
        }
}

class VaultPinTest {
    private var now = 1_790_000_000_000L
    private val store = MemoryVaultStore()
    private val pins = VaultPinStore(store, { now }, iterations = 50)

    @Test
    fun `correct and wrong PIN`() {
        assertEquals(VaultPinStore.Result.NotSet, pins.verify(SCOPE, "1234"))
        pins.set(SCOPE, "1234")
        assertEquals(VaultPinStore.Result.Success, pins.verify(SCOPE, "1234"))
        assertTrue(pins.verify(SCOPE, "4321") is VaultPinStore.Result.Wrong)
    }

    @Test
    fun `stored hashed and salted, never in clear`() {
        pins.set(SCOPE, "4711")
        assertTrue(store.values.values.none { it.contains("4711") })
        val first = store.values.values.single()
        pins.set(SCOPE, "4711")
        // a new salt every time
        assertTrue(first != store.values.values.single())
    }

    @Test
    fun `scoped per server and user`() {
        pins.set(SCOPE, "1111")
        assertFalse(pins.isSet(OTHER_USER))
        assertFalse(pins.isSet(OTHER_SERVER))
        assertEquals(VaultPinStore.Result.NotSet, pins.verify(OTHER_USER, "1111"))
    }

    @Test
    fun `four symbols, digits or D-pad directions`() {
        assertTrue(VaultPinStore.isValid("1234"))
        assertTrue(VaultPinStore.isValid("URDL"))
        assertFalse(VaultPinStore.isValid("123"))
        assertFalse(VaultPinStore.isValid("12345"))
        assertFalse(VaultPinStore.isValid("12a4"))
    }

    @Test
    fun `wrong guesses lock out, and a locked out PIN isn't even checked`() {
        pins.set(SCOPE, "2468")
        repeat(5) {
            val result = pins.verify(SCOPE, "0000") as VaultPinStore.Result.Wrong
            assertNull(result.lockedUntilMs)
        }
        val sixth = pins.verify(SCOPE, "0000") as VaultPinStore.Result.Wrong
        assertEquals(now + 30_000, sixth.lockedUntilMs)
        assertTrue(pins.verify(SCOPE, "2468") is VaultPinStore.Result.LockedOut)
        now += 30_001
        assertTrue(pins.verify(SCOPE, "0000") is VaultPinStore.Result.Wrong)
        assertEquals(now + 60_000, pins.lockedUntil(SCOPE))
        now += 60_001
        assertEquals(VaultPinStore.Result.Success, pins.verify(SCOPE, "2468"))
        // counters reset on success
        assertNull((pins.verify(SCOPE, "0000") as VaultPinStore.Result.Wrong).lockedUntilMs)
    }

    @Test
    fun `lockout grows to at most 15 minutes and survives a restart`() {
        assertEquals(0L, VaultPinStore.lockoutFor(5))
        assertEquals(30_000L, VaultPinStore.lockoutFor(6))
        assertEquals(15 * 60_000L, VaultPinStore.lockoutFor(100))
        pins.set(SCOPE, "1234")
        repeat(6) { pins.verify(SCOPE, "0000") }
        val restarted = VaultPinStore(store, { now }, iterations = 50)
        assertTrue(restarted.verify(SCOPE, "1234") is VaultPinStore.Result.LockedOut)
    }

    @Test
    fun `the PIN is never part of the synced config`() =
        runTest {
            val device = Device(FakeJellyfin().apply { seedStandard() }, backgroundScope)
            device.vault.pins.set(SCOPE, "1357")
            device.configure()
            assertFalse(
                device.service.config
                    .encode()
                    .contains("pin", ignoreCase = true),
            )
        }
}
