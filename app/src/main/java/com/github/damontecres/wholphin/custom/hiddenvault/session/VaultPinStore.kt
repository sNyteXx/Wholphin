package com.github.damontecres.wholphin.custom.hiddenvault.session

import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultKeyValueStore
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultScope
import com.github.damontecres.wholphin.custom.hiddenvault.data.VaultStorageKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * The vault's own PIN, per server and user, on this device only.
 *
 * Deliberately separate from Wholphin's profile PIN (which is stored in clear and has no attempt
 * limit): never synced, never stored in clear (PBKDF2-HMAC-SHA256 with a random salt), and wrong
 * guesses lock out. Symbols follow Wholphin's PIN entry: digits and the four D-pad directions
 * (`U`, `R`, `D`, `L`), so a remote without number keys works.
 */
class VaultPinStore(
    private val store: VaultKeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
) {
    sealed interface Result {
        data object Success : Result

        data object NotSet : Result

        /** Wrong PIN; [lockedUntilMs] is set when this guess started a lockout */
        data class Wrong(
            val freeAttemptsLeft: Int,
            val lockedUntilMs: Long?,
        ) : Result

        /** Locked out; the PIN was not even checked */
        data class LockedOut(
            val untilMs: Long,
        ) : Result
    }

    @Serializable
    private data class Stored(
        val salt: String,
        val hash: String,
        val iterations: Int,
        val failed: Int = 0,
        val lockedUntil: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun load(scope: VaultScope): Stored? =
        store.get(VaultStorageKeys.pin(scope))?.let {
            try {
                json.decodeFromString(Stored.serializer(), it)
            } catch (_: Exception) {
                null
            }
        }

    private fun save(
        scope: VaultScope,
        stored: Stored,
    ) = store.put(VaultStorageKeys.pin(scope), json.encodeToString(Stored.serializer(), stored))

    @Synchronized
    fun isSet(scope: VaultScope): Boolean = load(scope) != null

    /** When the current lockout ends, or null when not locked out */
    @Synchronized
    fun lockedUntil(scope: VaultScope): Long? = load(scope)?.lockedUntil?.takeIf { it > clock() }

    @Synchronized
    fun set(
        scope: VaultScope,
        pin: String,
    ) {
        require(isValid(pin)) { "PIN must be $LENGTH symbols" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        save(scope, Stored(salt.hex(), hash(pin, salt, iterations).hex(), iterations))
    }

    @Synchronized
    fun remove(scope: VaultScope) = store.remove(VaultStorageKeys.pin(scope))

    @Synchronized
    fun verify(
        scope: VaultScope,
        pin: String,
    ): Result {
        val stored = load(scope) ?: return Result.NotSet
        val now = clock()
        if (stored.lockedUntil > now) return Result.LockedOut(stored.lockedUntil)
        val actual = hash(pin, stored.salt.unhex(), stored.iterations)
        if (MessageDigest.isEqual(actual, stored.hash.unhex())) {
            if (stored.failed != 0 || stored.lockedUntil != 0L) save(scope, stored.copy(failed = 0, lockedUntil = 0L))
            return Result.Success
        }
        val failed = stored.failed + 1
        val lockout = lockoutFor(failed)
        val lockedUntil = if (lockout > 0) now + lockout else 0L
        save(scope, stored.copy(failed = failed, lockedUntil = lockedUntil))
        return Result.Wrong((FREE_ATTEMPTS - failed).coerceAtLeast(0), lockedUntil.takeIf { it > 0 })
    }

    companion object {
        const val LENGTH = 4
        const val FREE_ATTEMPTS = 5
        const val LOCKOUT_STEP_MS = 30_000L
        const val MAX_LOCKOUT_MS = 15 * 60_000L
        const val DEFAULT_ITERATIONS = 10_000
        const val SYMBOLS = "0123456789URDL"

        fun isValid(pin: String): Boolean = pin.length == LENGTH && pin.all { it in SYMBOLS }

        /** Five free attempts, then 30 s more per wrong guess, at most 15 min */
        fun lockoutFor(failedAttempts: Int): Long =
            if (failedAttempts <= FREE_ATTEMPTS) {
                0L
            } else {
                (LOCKOUT_STEP_MS * (failedAttempts - FREE_ATTEMPTS)).coerceAtMost(MAX_LOCKOUT_MS)
            }

        /** PBKDF2-HMAC-SHA256, one block; written out so it works on every API level */
        internal fun hash(
            pin: String,
            salt: ByteArray,
            iterations: Int,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pin.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var block = mac.doFinal()
            val result = block.copyOf()
            repeat(iterations - 1) {
                block = mac.doFinal(block)
                for (i in result.indices) result[i] = result[i] xor block[i]
            }
            return result
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
