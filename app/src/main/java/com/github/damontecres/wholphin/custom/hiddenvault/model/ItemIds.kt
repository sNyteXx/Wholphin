package com.github.damontecres.wholphin.custom.hiddenvault.model

import java.util.UUID

/**
 * One spelling for Jellyfin ids everywhere in the vault.
 *
 * The server writes ids without dashes in JSON, the SDK hands out [UUID]s, and library ids may
 * arrive in either form from older configs. Everything is compared as 32 lower case hex digits.
 */
object ItemIds {
    fun normalize(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val stripped = id.trim().replace("-", "").lowercase()
        return stripped.ifEmpty { null }
    }

    fun of(id: UUID?): String? = id?.let { normalize(it.toString()) }

    /**
     * The [UUID] for a normalized id, or null when it isn't one
     */
    fun toUuid(id: String?): UUID? {
        val normalized = normalize(id) ?: return null
        if (normalized.length != 32) return null
        return try {
            UUID.fromString(
                buildString {
                    append(normalized, 0, 8)
                    append('-')
                    append(normalized, 8, 12)
                    append('-')
                    append(normalized, 12, 16)
                    append('-')
                    append(normalized, 16, 20)
                    append('-')
                    append(normalized, 20, 32)
                },
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
