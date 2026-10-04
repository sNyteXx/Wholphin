package com.github.damontecres.wholphin.custom.hiddenvault.visibility

import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * The few facts about a server item that visibility depends on.
 *
 * Built from raw JSON in the API decorator (no full DTO parse) or from a [BaseItemDto] in the
 * gates. [tags] is null when the response carried no tag field at all, which is different from an
 * empty list: an item fetched without the field says nothing about its tags.
 */
data class ItemRef(
    val id: String?,
    val type: String? = null,
    val seriesId: String? = null,
    val seasonId: String? = null,
    val parentId: String? = null,
    val albumId: String? = null,
    val tags: List<String>? = null,
) {
    /** Whether this item hangs below [anchorId] (its series, season, parent folder or album) */
    fun isChildOf(anchorId: String?): Boolean =
        anchorId != null &&
            (seriesId == anchorId || seasonId == anchorId || parentId == anchorId || albumId == anchorId)

    companion object {
        fun of(json: JsonObject): ItemRef =
            ItemRef(
                id = ItemIds.normalize(json.string("Id")),
                type = json.string("Type"),
                seriesId = ItemIds.normalize(json.string("SeriesId")),
                seasonId = ItemIds.normalize(json.string("SeasonId")),
                parentId = ItemIds.normalize(json.string("ParentId")),
                albumId = ItemIds.normalize(json.string("AlbumId")),
                tags = json.tags(),
            )

        fun of(dto: BaseItemDto): ItemRef =
            ItemRef(
                id = ItemIds.of(dto.id),
                type = dto.type.serialName,
                seriesId = ItemIds.of(dto.seriesId),
                seasonId = ItemIds.of(dto.seasonId),
                parentId = ItemIds.of(dto.parentId),
                albumId = ItemIds.of(dto.albumId),
                tags = dto.tags,
            )

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.tags(): List<String>? {
            val tags = this["Tags"] ?: return null
            if (tags !is JsonArray) return null
            return tags.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        }
    }
}

/**
 * What the rules say about one item
 */
sealed interface Verdict {
    val isHidden: Boolean

    data object Visible : Verdict {
        override val isHidden = false
    }

    /**
     * Hidden from the normal app.
     *
     * @param vaultId the vault the item belongs to; null when the item is hidden but its vault
     * isn't known (a tagged collection, or a tag match the index couldn't place yet). Such items
     * are never shown anywhere.
     * @param suspectId set when the item's own tags match a rule but the index hasn't placed it in
     * a covered library yet. It stays hidden until an index rebuild decides.
     */
    data class Hidden(
        val vaultId: String?,
        val suspectId: String? = null,
    ) : Verdict {
        override val isHidden = true
    }
}
