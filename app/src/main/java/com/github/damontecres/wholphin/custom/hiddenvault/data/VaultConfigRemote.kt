package com.github.damontecres.wholphin.custom.hiddenvault.data

import com.github.damontecres.wholphin.custom.hiddenvault.model.HiddenVaultConfig

/**
 * Where the config of one user travels between devices
 */
interface VaultConfigRemote {
    /** The copy on the server, or null when there is none */
    suspend fun pull(): HiddenVaultConfig?

    /** Replaces the server's copy with [config] */
    suspend fun push(config: HiddenVaultConfig)
}

/**
 * Logging seam so the core stays free of Android classes. The app routes it to Timber.
 */
fun interface VaultLog {
    fun log(
        message: String,
        error: Throwable?,
    )

    fun d(message: String) = log(message, null)

    fun w(
        message: String,
        error: Throwable? = null,
    ) = log(message, error)

    companion object {
        val NONE = VaultLog { _, _ -> }
    }
}
