package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenTagPolicy
import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.model.TagMatch
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultDefinition
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultLibrary
import com.github.damontecres.wholphin.custom.hiddenvault.model.VaultSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagMatchTest {
    private fun matches(
        tag: String,
        vararg hidden: String,
    ) = TagMatch.matchesAny(listOf(tag), TagMatch.normalizeAll(hidden.toList()))

    @Test
    fun `matches regardless of case and surrounding whitespace`() {
        assertTrue(matches("ecchi", "ecchi"))
        assertTrue(matches("Ecchi", "ecchi"))
        assertTrue(matches("ECCHI", "ecchi"))
        assertTrue(matches("  Ecchi  ", "ecchi"))
        assertTrue(matches("ecchi", "ECCHI"))
    }

    @Test
    fun `collapses inner whitespace runs`() {
        assertTrue(matches("Private   Stuff", "private stuff"))
        assertTrue(matches("private\tstuff", "Private Stuff"))
    }

    @Test
    fun `never matches a substring or a longer tag`() {
        assertFalse(matches("super-ecchi", "ecchi"))
        assertFalse(matches("ecchi comedy", "ecchi"))
        assertFalse(matches("ecchi-ish", "ecchi"))
        assertFalse(matches("ecch", "ecchi"))
        assertFalse(matches("Ecchi!", "ecchi"))
    }

    @Test
    fun `adult does not match adult animation`() {
        assertFalse(matches("adult animation", "adult"))
        assertFalse(TagMatch.matchesAny(listOf("Adult Animation", "nudity"), setOf("adult")))
        assertTrue(matches("Adult", "adult"))
    }

    @Test
    fun `hidden does not match hidden gem`() {
        assertFalse(matches("hidden gem", "hidden"))
        assertTrue(matches("Hidden", "hidden"))
    }

    @Test
    fun `several hidden tags hide on any of them`() {
        val hidden = TagMatch.normalizeAll(listOf("ecchi", "private"))
        assertTrue(TagMatch.matchesAny(listOf("romance", "Private"), hidden))
        assertTrue(TagMatch.matchesAny(listOf("ecchi"), hidden))
        assertFalse(TagMatch.matchesAny(listOf("romance", "anime"), hidden))
    }

    @Test
    fun `empty and blank tags are ignored`() {
        assertEquals(setOf("a"), TagMatch.normalizeAll(listOf("", "  ", "a")))
        assertFalse(TagMatch.matchesAny(listOf("", " "), setOf("a")))
        assertFalse(TagMatch.matchesAny(listOf("anything"), emptySet()))
    }

    @Test
    fun `ids are compared without dashes and case`() {
        val uuid = FakeJellyfin.uuidOf("x")
        assertEquals(ItemIds.of(uuid), ItemIds.normalize(uuid.toString().uppercase()))
        assertEquals(uuid, ItemIds.toUuid(ItemIds.of(uuid)))
        assertNull(ItemIds.normalize("  "))
    }
}

class VaultConfigTest {
    private val config =
        HiddenVaultConfig(
            vaults =
                listOf(
                    VaultDefinition(
                        "anime",
                        "Anime",
                        listOf(
                            VaultLibrary(lib("lib-anime"), "Anime", "tvshows", listOf("ecchi")),
                            VaultLibrary(lib("lib-anime-movies"), "Anime films", "movies", listOf("ecchi", "Private")),
                        ),
                    ),
                    VaultDefinition("shows", "Shows", listOf(VaultLibrary(lib("lib-shows"), "Shows", "tvshows", listOf("private")))),
                ),
        ).normalized()
    private val policy = HiddenTagPolicy.from(config)

    @Test
    fun `anime tags never apply to the shows library`() {
        assertTrue(policy.isHiddenInLibrary(listOf("ecchi"), lib("lib-anime")))
        assertFalse(policy.isHiddenInLibrary(listOf("ecchi"), lib("lib-shows")))
        assertFalse(policy.isHiddenInLibrary(listOf("ecchi"), lib("lib-unconfigured")))
    }

    @Test
    fun `each library keeps its own tags`() {
        assertFalse(policy.isHiddenInLibrary(listOf("private"), lib("lib-anime")))
        assertTrue(policy.isHiddenInLibrary(listOf("PRIVATE"), lib("lib-anime-movies")))
        assertTrue(policy.isHiddenInLibrary(listOf("private"), lib("lib-shows")))
    }

    @Test
    fun `a library belongs to one vault only`() {
        val clash =
            HiddenVaultConfig(
                vaults =
                    listOf(
                        VaultDefinition("a", "A", listOf(VaultLibrary(lib("lib"), tags = listOf("x")))),
                        VaultDefinition("b", "B", listOf(VaultLibrary(lib("lib"), tags = listOf("y")))),
                    ),
            ).normalized()
        assertTrue(clash.vaults[1].libraries.isEmpty())
        assertEquals("a", HiddenTagPolicy.from(clash).ruleFor(lib("lib"))?.vaultId)
    }

    @Test
    fun `library identity is the id, in any spelling`() {
        val dashed = FakeJellyfin.uuidOf("lib-anime").toString().uppercase()
        assertEquals("anime", config.vaultForLibrary(dashed)?.id)
        assertTrue(policy.isHiddenInLibrary(listOf("ecchi"), dashed))
    }

    @Test
    fun `fingerprint follows the rules, not names or session settings`() {
        val renamed =
            config.copy(
                vaults = config.vaults.map { it.copy(name = it.name + "!") },
                settings = VaultSettings(autoLockMinutes = 60, lockOnLeave = false, hideWatched = true),
            )
        assertEquals(config.fingerprint, renamed.fingerprint)
        val recased =
            config.copy(
                vaults =
                    config.vaults.map { v ->
                        v.copy(libraries = v.libraries.map { it.copy(tags = it.tags.map(String::uppercase)) })
                    },
            )
        assertEquals(config.fingerprint, recased.fingerprint)
        val changed =
            config.copy(vaults = listOf(VaultDefinition("anime", "Anime", listOf(VaultLibrary(lib("lib-anime"), tags = listOf("nudity"))))))
        assertNotEquals(config.fingerprint, changed.fingerprint)
    }

    @Test
    fun `config survives a save and load`() {
        val decoded = HiddenVaultConfig.decode(config.encode())
        assertEquals(config.fingerprint, decoded.fingerprint)
        assertEquals("Anime", decoded.vaults.first().name)
        assertEquals(
            listOf("ecchi", "Private"),
            decoded.vaults
                .first()
                .libraries
                .last()
                .tags,
        )
    }

    @Test
    fun `duplicate tags collapse but keep the first spelling`() {
        val library =
            config.copy(
                vaults = listOf(VaultDefinition("v", "V", listOf(VaultLibrary(lib("x"), tags = listOf("Ecchi", "ecchi ", "ECCHI"))))),
            )
        assertEquals(
            listOf("Ecchi"),
            library
                .normalized()
                .vaults
                .single()
                .libraries
                .single()
                .tags,
        )
    }

    @Test
    fun `no hard coded defaults`() {
        assertTrue(HiddenVaultConfig.EMPTY.vaults.isEmpty())
        assertFalse(HiddenTagPolicy.from(HiddenVaultConfig.EMPTY).isActive)
        assertEquals("0", HiddenVaultConfig.EMPTY.fingerprint)
        assertEquals(15, VaultSettings().autoLockMinutes)
        assertTrue(VaultSettings().lockOnLeave)
    }

    @Test
    fun `a broken stored copy reads as empty`() {
        assertEquals(HiddenVaultConfig.EMPTY, HiddenVaultConfig.decode("{not json"))
        assertEquals(HiddenVaultConfig.EMPTY, HiddenVaultConfig.decode(null))
    }
}
