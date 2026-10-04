package com.github.damontecres.wholphin.custom.hiddenvault.visibility

import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService

/**
 * What the visibility layer needs to know about the moment a call is made in
 */
interface HiddenVaultRuntime {
    /**
     * The rules of the signed in account once its first sync is in and an index is available, or
     * null when there are none (every call then passes straight through)
     */
    suspend fun activeService(): HiddenContentService?

    /** The rules of the signed in account as they are right now, without waiting for anything */
    fun loadedService(): HiddenContentService?

    /** The vaults of the signed in account that are unlocked and currently open */
    fun enteredVaults(): Set<String>

    /** Something of [vaultId] was just let through or played, which keeps the vault open */
    fun noteVaultActivity(vaultId: String)
}

/**
 * Decides whether a hidden item may still be shown in one particular call.
 *
 * Global lists never make exceptions. Only calls about one particular item (the item itself, its
 * seasons and episodes, its extras and similar items) let a vault's own items through, and only
 * while that vault is unlocked and open. Unlocking a vault never changes what the normal app
 * shows.
 */
class VaultAllowance private constructor(
    private val entered: Set<String>,
    private val kind: AllowanceKind,
    private val anchorId: String?,
    private val anchorVault: String?,
    private val onAllowed: (String) -> Unit,
) {
    fun allows(
        ref: ItemRef,
        vaultId: String?,
    ): Boolean {
        if (vaultId == null || vaultId !in entered) return false
        val allowed =
            when (kind) {
                AllowanceKind.NONE -> {
                    false
                }

                AllowanceKind.SELF -> {
                    anchorId != null && ref.id == anchorId
                }

                AllowanceKind.CHILDREN -> {
                    anchorId != null && (ref.id == anchorId || ref.isChildOf(anchorId))
                }

                AllowanceKind.RELATED -> {
                    anchorId != null && (anchorVault == vaultId || ref.isChildOf(anchorId))
                }
            }
        if (allowed) onAllowed(vaultId)
        return allowed
    }

    companion object {
        val NONE = VaultAllowance(emptySet(), AllowanceKind.NONE, null, null) {}

        fun of(
            kind: AllowanceKind,
            anchorId: String?,
            service: HiddenContentService,
            runtime: HiddenVaultRuntime,
        ): VaultAllowance {
            if (kind == AllowanceKind.NONE || anchorId == null) return NONE
            val entered = runtime.enteredVaults()
            if (entered.isEmpty()) return NONE
            return VaultAllowance(
                entered,
                kind,
                anchorId,
                service.vaultOfId(anchorId),
                runtime::noteVaultActivity,
            )
        }
    }
}
