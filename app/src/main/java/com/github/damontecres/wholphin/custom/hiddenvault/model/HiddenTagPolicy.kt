package com.github.damontecres.wholphin.custom.hiddenvault.model

/**
 * The rule one library contributes: which vault owns it and which tags hide an item inside it
 */
data class LibraryRule(
    val vaultId: String,
    val libraryId: String,
    val tags: Set<String>,
)

/**
 * The configured hiding rules in the form a check needs.
 *
 * Answers "would these tags hide an item in that library" exactly and ignoring case. A server item
 * doesn't say which library it sits in, so the scoped answer is what the hidden index is built
 * from; the unscoped [matchesAnyRule] is only a hint that an item deserves a closer look.
 */
class HiddenTagPolicy private constructor(
    private val rulesByLibrary: Map<String, LibraryRule>,
    /** Every hidden tag of every library, for the cheap "could this be hidden" hint */
    val allTags: Set<String>,
    /** The config fingerprint this policy was built from */
    val fingerprint: String,
) {
    val isActive: Boolean get() = rulesByLibrary.isNotEmpty()

    val rules: Collection<LibraryRule> get() = rulesByLibrary.values

    val libraryIds: Set<String> get() = rulesByLibrary.keys

    fun ruleFor(libraryId: String?): LibraryRule? = ItemIds.normalize(libraryId)?.let { rulesByLibrary[it] }

    /** Whether an item in [libraryId] carrying [tags] is hidden there */
    fun isHiddenInLibrary(
        tags: Iterable<String>,
        libraryId: String,
    ): Boolean {
        val rule = ruleFor(libraryId) ?: return false
        return TagMatch.matchesAny(tags, rule.tags)
    }

    /** Whether [tags] would hide an item in at least one configured library */
    fun matchesAnyRule(tags: Iterable<String>): Boolean = TagMatch.matchesAny(tags, allTags)

    /**
     * The hidden tags of [libraryId], or every hidden tag when no library is given. Used to keep
     * hidden tags out of filter pickers.
     */
    fun tagsForScope(libraryId: String?): Set<String> {
        if (libraryId == null) return allTags
        return ruleFor(libraryId)?.tags ?: emptySet()
    }

    companion object {
        val NONE = HiddenTagPolicy(emptyMap(), emptySet(), "0")

        fun from(config: HiddenVaultConfig): HiddenTagPolicy {
            val rules = mutableMapOf<String, LibraryRule>()
            val all = mutableSetOf<String>()
            val normalized = config.normalized()
            normalized.vaults.forEach { vault ->
                vault.libraries.forEach { library ->
                    if (library.hasTags) {
                        rules[library.libraryId] =
                            LibraryRule(vault.id, library.libraryId, library.normalizedTags)
                        all.addAll(library.normalizedTags)
                    }
                }
            }
            if (rules.isEmpty()) return NONE
            return HiddenTagPolicy(rules.toMap(), all.toSet(), normalized.fingerprint)
        }
    }
}
