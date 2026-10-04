package com.github.damontecres.wholphin.custom.hiddenvault.data

import java.io.File
import java.io.IOException

/**
 * Which signed in account a piece of vault state belongs to.
 *
 * Library ids only mean something on their own server, and two people on one device must never
 * share a vault, so everything is kept per server and user.
 */
data class VaultScope(
    val serverId: String,
    val userId: String,
) {
    val isValid: Boolean get() = serverId.isNotBlank() && userId.isNotBlank()

    /** Stable storage form, reduced to characters every file system accepts */
    val key: String get() = "${safe(serverId)}.${safe(userId)}"

    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_")
}

/**
 * The few string operations the vault persists through. Small on purpose, so tests hand in a map
 * and the app hands in files.
 */
interface VaultKeyValueStore {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )

    fun remove(key: String)

    fun keys(): Set<String>
}

class MemoryVaultStore : VaultKeyValueStore {
    val values = mutableMapOf<String, String>()

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun put(
        key: String,
        value: String,
    ) {
        values[key] = value
    }

    @Synchronized
    override fun remove(key: String) {
        values.remove(key)
    }

    @Synchronized
    override fun keys(): Set<String> = values.keys.toSet()
}

/**
 * One small file per key inside [directory].
 *
 * The app points this at `noBackupFilesDir` so neither the PIN hash nor the rules end up in a
 * cloud backup, and it stays out of the shared preferences that crash reports include. Writes go
 * to a temporary file first and are then renamed, so a crash never leaves half a file behind.
 */
class FileVaultStore(
    private val directory: File,
) : VaultKeyValueStore {
    private fun fileFor(key: String) = File(directory, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    @Synchronized
    override fun get(key: String): String? {
        val file = fileFor(key)
        return try {
            if (file.exists()) file.readText() else null
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    override fun put(
        key: String,
        value: String,
    ) {
        directory.mkdirs()
        val target = fileFor(key)
        val temp = File(directory, "${target.name}.tmp")
        temp.writeText(value)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Could not write ${target.name}")
        }
    }

    @Synchronized
    override fun remove(key: String) {
        fileFor(key).delete()
    }

    @Synchronized
    override fun keys(): Set<String> =
        directory
            .listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.map { it.name }
            ?.toSet()
            .orEmpty()
}

/**
 * Storage keys, versioned so a later format can move without misreading the old one. There is
 * deliberately no key for an unlocked state.
 */
object VaultStorageKeys {
    fun config(scope: VaultScope) = "hidden_vault.v1.config.${scope.key}"

    fun index(scope: VaultScope) = "hidden_vault.v1.index.${scope.key}"

    fun outOfScope(scope: VaultScope) = "hidden_vault.v1.outofscope.${scope.key}"

    fun device(scope: VaultScope) = "hidden_vault.v1.device.${scope.key}"

    fun pin(scope: VaultScope) = "hidden_vault.v1.pin.${scope.key}"
}

/**
 * Choices that belong to this device only and never travel with the synced config
 */
data class VaultDeviceSettings(
    val syncEnabled: Boolean = true,
) {
    fun encode(): String = if (syncEnabled) "" else "nosync"

    companion object {
        fun decode(raw: String?): VaultDeviceSettings = VaultDeviceSettings(syncEnabled = raw?.split(',')?.contains("nosync") != true)
    }
}
