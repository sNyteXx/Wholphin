package com.github.damontecres.wholphin.custom.hiddenvault.ui

import kotlinx.serialization.Serializable

/**
 * The vault's own pages, carried by the single upstream destination
 * [com.github.damontecres.wholphin.ui.nav.Destination.HiddenVault].
 *
 * Only ids travel here (they end up in the saved back stack); the content behind them always
 * needs the in-memory session, so a restored back stack lands on a locked page that leaves at once.
 */
@Serializable
sealed interface VaultRoute {
    /** The vault this page shows, or null for pages of the private area itself */
    val vaultId: String?

    /** The list of vaults and the settings, reached with the PIN */
    @Serializable
    data object PrivateArea : VaultRoute {
        override val vaultId: String? get() = null
    }

    /** Creates ([editVaultId] null) or edits a vault */
    @Serializable
    data class Editor(
        val editVaultId: String?,
    ) : VaultRoute {
        override val vaultId: String? get() = null
    }

    @Serializable
    data class Home(
        override val vaultId: String,
    ) : VaultRoute

    @Serializable
    data class Library(
        override val vaultId: String,
        val libraryId: String,
    ) : VaultRoute

    @Serializable
    data class Search(
        override val vaultId: String,
    ) : VaultRoute
}
