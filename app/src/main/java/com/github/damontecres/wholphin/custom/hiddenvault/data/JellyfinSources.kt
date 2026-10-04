package com.github.damontecres.wholphin.custom.hiddenvault.data

import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.ItemRef
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest

/**
 * Index queries over the **unfiltered** client. Never hand the app's filtered client in here: it
 * would hide exactly the items the index is looking for.
 */
class JellyfinIndexSource(
    private val rawApi: ApiClient,
) : HiddenIndexSource {
    override suspend fun taggedItems(
        libraryId: String,
        tags: List<String>,
        startIndex: Int,
        limit: Int,
    ): IndexPage {
        val result =
            try {
                rawApi.itemsApi
                    .getItems(
                        GetItemsRequest(
                            parentId = ItemIds.toUuid(libraryId),
                            recursive = true,
                            tags = tags,
                            fields = listOf(ItemFields.TAGS),
                            sortBy = listOf(ItemSortBy.SORT_NAME),
                            sortOrder = listOf(SortOrder.ASCENDING),
                            startIndex = startIndex,
                            limit = limit,
                            enableImages = false,
                            imageTypeLimit = 0,
                            enableUserData = false,
                            enableTotalRecordCount = true,
                        ),
                    ).content
            } catch (ex: InvalidStatusException) {
                // A library deleted on the server holds nothing to hide
                if (ex.status == 404) return IndexPage(emptyList(), 0)
                throw ex
            }
        return IndexPage(result.items.map { ItemRef.of(it) }, result.totalRecordCount)
    }

    override suspend fun libraryIds(): Set<String> =
        rawApi.userViewsApi
            .getUserViews()
            .content.items
            .mapNotNullTo(mutableSetOf()) { ItemIds.of(it.id) }
}

/**
 * Carries the config between a user's devices through the server's own per-user display
 * preferences, so it needs no plugin and reaches every device signed in as that user. Only the
 * rules and session settings travel; the PIN, the index and the unlock state never leave the
 * device.
 *
 * Reading goes through Wholphin's `DisplayPreferencesService`; writing is done here because the
 * service's update always starts from the `default` preferences.
 */
class DisplayPreferencesVaultRemote(
    private val read: suspend () -> DisplayPreferencesDto,
    private val write: suspend (DisplayPreferencesDto) -> Unit,
) : VaultConfigRemote {
    override suspend fun pull(): HiddenVaultConfig? {
        val raw = read().customPrefs[KEY]
        if (raw.isNullOrBlank()) return null
        val config = HiddenVaultConfig.decode(raw)
        return if (config.updatedAt == 0L && config.vaults.isEmpty()) null else config
    }

    override suspend fun push(config: HiddenVaultConfig) {
        val current = read()
        write(current.copy(customPrefs = current.customPrefs + (KEY to config.encode())))
    }

    companion object {
        /** Own namespace, never shared with Wholphin's or the web client's preferences */
        const val PREFERENCES_ID = "wholphin-hidden-vault"
        const val CLIENT = "wholphin-hidden-vault"
        const val KEY = "config"
    }
}
