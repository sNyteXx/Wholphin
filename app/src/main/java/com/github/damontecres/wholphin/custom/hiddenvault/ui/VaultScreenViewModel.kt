package com.github.damontecres.wholphin.custom.hiddenvault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.custom.hiddenvault.HiddenVault
import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultRepository
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultScope
import com.github.damontecres.wholphin.custom.hiddenvault.visibility.HiddenContentApiClient
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.components.ContextMenuProvider
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import org.jellyfin.sdk.api.client.ApiClient
import java.util.UUID

/**
 * Base of the screens inside one vault (home, library grid, search).
 *
 * The screen counts as "in the vault" for as long as its back stack entry lives: entering on
 * creation and leaving when the entry is popped. Detail and player pages opened from here sit on
 * top of it, so the vault stays open for them; going back past the vault's home leaves it, which
 * locks it unless "lock when leaving" is off.
 */
abstract class VaultScreenViewModel(
    protected val vault: HiddenVault,
    api: ApiClient,
    val navigationManager: NavigationManager,
    private val backdropService: BackdropService,
    private val favoriteWatchManager: FavoriteWatchManager,
    val vaultId: String,
) : ViewModel(),
    ContextMenuProvider {
    protected val scope: VaultScope? = vault.activeScope.value
    protected val service: HiddenContentService? = vault.currentService()
    protected val client: HiddenContentApiClient? = api as? HiddenContentApiClient

    /** The vault is unlocked for this account and this screen may show it */
    val isOpen: Boolean = scope != null && service != null && client != null && vault.session.isUnlocked(scope, vaultId)

    protected val repository: VaultRepository? =
        if (isOpen) service?.config?.vault(vaultId)?.let { VaultRepository(service, it, client!!.raw) } else null

    init {
        if (isOpen) vault.session.enter(scope!!, vaultId)
    }

    protected fun touch() {
        scope?.let { vault.session.touch(it, vaultId) }
    }

    fun updateBackdrop(item: BaseItem) {
        touch()
        viewModelScope.launchIO { backdropService.submit(item) }
    }

    override fun onCleared() {
        if (isOpen) vault.session.leave(scope!!, vaultId)
    }

    // ContextMenuProvider: the same long press menu as everywhere else

    override fun isAdministrator(): Boolean = false

    override fun navigateTo(destination: Destination) {
        touch()
        navigationManager.navigateTo(destination)
    }

    override fun canDelete(
        item: BaseItem,
        appPreferences: AppPreferences,
    ): Boolean = false

    override fun deleteItem(
        index: Int,
        item: BaseItem,
    ) = Unit

    override fun setWatched(
        position: Int,
        itemId: UUID,
        played: Boolean,
    ) {
        touch()
        viewModelScope.launchIO {
            favoriteWatchManager.setWatched(itemId, played)
            onItemChanged()
        }
    }

    override fun setFavorite(
        position: Int,
        itemId: UUID,
        favorite: Boolean,
    ) {
        touch()
        viewModelScope.launchIO {
            favoriteWatchManager.setFavorite(itemId, favorite)
            onItemChanged()
        }
    }

    override fun sendReportFor(itemId: UUID) = Unit

    /** An item was marked watched or favorite; reload what shows it */
    protected abstract suspend fun onItemChanged()
}
