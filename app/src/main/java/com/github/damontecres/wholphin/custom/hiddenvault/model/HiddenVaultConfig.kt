package com.github.damontecres.wholphin.custom.hiddenvault.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * One library a vault covers, and the tags that hide an item in it.
 *
 * The library id is the identity. [name] and [collectionType] are snapshots for the settings
 * screen and the vault's own rows, so renaming a library on the server changes nothing about what
 * is hidden.
 */
@Serializable
data class VaultLibrary(
    val libraryId: String,
    val name: String = "",
    val collectionType: String? = null,
    /** The tags as they were picked or typed, kept for display */
    val tags: List<String> = emptyList(),
) {
    /** The comparison form of [tags] */
    @Transient
    val normalizedTags: Set<String> = TagMatch.normalizeAll(tags)

    val hasTags: Boolean get() = normalizedTags.isNotEmpty()
}

/**
 * A group of libraries that hide and reveal together, such as "Anime" plus "Anime films"
 */
@Serializable
data class VaultDefinition(
    /** Stable id, never shown and never derived from the name */
    val id: String,
    val name: String,
    val libraries: List<VaultLibrary> = emptyList(),
) {
    val hasRules: Boolean get() = libraries.any { it.hasTags }

    fun library(libraryId: String): VaultLibrary? {
        val normalized = ItemIds.normalize(libraryId)
        return libraries.firstOrNull { it.libraryId == normalized }
    }
}

/**
 * Session behaviour, kept apart from the rules so changing a timeout never throws away a cache
 */
@Serializable
data class VaultSettings(
    val autoLockMinutes: Int = DEFAULT_AUTO_LOCK_MINUTES,
    val lockOnLeave: Boolean = true,
    /**
     * Vault rows, grids and search only list what hasn't been watched yet. Continue watching and
     * next up are unwatched by nature and stay as they are.
     */
    val hideWatched: Boolean = false,
) {
    val autoLockMillis: Long get() = autoLockMinutes.coerceAtLeast(1) * 60_000L

    companion object {
        const val DEFAULT_AUTO_LOCK_MINUTES = 15
        val AUTO_LOCK_CHOICES = listOf(5, 15, 30, 60)
    }
}

/**
 * Everything one user on one server configured. Synced between devices through the user's
 * Jellyfin display preferences, last writer wins on [updatedAt].
 *
 * Nothing device specific lives here: no PIN, no unlock state, no index.
 */
@Serializable
data class HiddenVaultConfig(
    val version: Int = CURRENT_VERSION,
    /** When this config was last saved on any device (ms since epoch, UTC) */
    val updatedAt: Long = 0L,
    val vaults: List<VaultDefinition> = emptyList(),
    val settings: VaultSettings = VaultSettings(),
) {
    val hasRules: Boolean get() = vaults.any { it.hasRules }

    fun vault(id: String): VaultDefinition? = vaults.firstOrNull { it.id == id }

    fun vaultForLibrary(libraryId: String): VaultDefinition? {
        val normalized = ItemIds.normalize(libraryId) ?: return null
        return vaults.firstOrNull { vault -> vault.libraries.any { it.libraryId == normalized } }
    }

    /**
     * Drops blank ids, normalizes library ids, de-duplicates tags and gives each library to the
     * first vault that names it, since an item can only ever belong to one vault.
     */
    fun normalized(): HiddenVaultConfig {
        val claimed = mutableSetOf<String>()
        val seenVaults = mutableSetOf<String>()
        val cleaned =
            vaults.mapNotNull { vault ->
                if (vault.id.isBlank() || !seenVaults.add(vault.id)) return@mapNotNull null
                val libraries =
                    vault.libraries.mapNotNull { library ->
                        val id = ItemIds.normalize(library.libraryId) ?: return@mapNotNull null
                        if (!claimed.add(id)) return@mapNotNull null
                        library.copy(libraryId = id, tags = dedupeTags(library.tags))
                    }
                vault.copy(name = vault.name.trim(), libraries = libraries)
            }
        return copy(vaults = cleaned)
    }

    /**
     * Changes exactly when what is hidden changes: vault ids, library ids and normalized tags.
     * Names and session settings are left out, so editing them keeps every cache.
     */
    @Transient
    val fingerprint: String = computeFingerprint(vaults)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val CURRENT_VERSION = 1

        val EMPTY = HiddenVaultConfig()

        internal val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }

        /**
         * The config in [source], or [EMPTY] when there is none or it can't be read. Never
         * throws: a broken copy must not take the app down.
         */
        fun decode(source: String?): HiddenVaultConfig {
            if (source.isNullOrBlank()) return EMPTY
            return try {
                json.decodeFromString(serializer(), source).normalized()
            } catch (_: Exception) {
                EMPTY
            }
        }

        private fun dedupeTags(tags: List<String>): List<String> {
            val seen = mutableSetOf<String>()
            return tags.mapNotNull { tag ->
                val trimmed = tag.trim()
                if (trimmed.isEmpty() || !seen.add(TagMatch.normalize(trimmed))) null else trimmed
            }
        }

        private fun computeFingerprint(vaults: List<VaultDefinition>): String {
            val parts = mutableListOf<String>()
            vaults.sortedBy { it.id }.forEach { vault ->
                vault.libraries.sortedBy { it.libraryId }.forEach { library ->
                    if (library.hasTags) {
                        parts.add(
                            "${vault.id}/${ItemIds.normalize(library.libraryId)}=" +
                                library.normalizedTags.sorted().joinToString("|"),
                        )
                    }
                }
            }
            return if (parts.isEmpty()) "0" else fnv1a(parts.joinToString(";"))
        }

        /** FNV-1a over the UTF-16 code units, stable across runs and devices */
        internal fun fnv1a(input: String): String {
            var hash = 0x811c9dc5L
            input.forEach { unit ->
                hash = ((hash xor unit.code.toLong()) * 0x01000193L) and 0xFFFFFFFFL
            }
            return hash.toString(16)
        }
    }
}
