package com.github.damontecres.wholphin.custom.hiddenvault.model

/**
 * Tag comparison for the hidden content vault.
 *
 * Matching is exact on the whole tag and ignores case: `Ecchi` matches `ecchi`, but `adult` never
 * matches `adult animation` and `hidden` never matches `hidden gem`. The normalized form is only
 * ever used for comparing; anything shown on screen keeps the server's spelling.
 */
object TagMatch {
    private val whitespaceRun = Regex("\\s+")

    /**
     * The comparison form of [tag]: trimmed, inner whitespace runs collapsed to one space, lower
     * cased. Empty when nothing is left.
     */
    fun normalize(tag: String): String = tag.trim().replace(whitespaceRun, " ").lowercase()

    /**
     * The normalized, de-duplicated, non-empty form of [tags]
     */
    fun normalizeAll(tags: Iterable<String>): Set<String> =
        buildSet {
            tags.forEach { tag ->
                val normalized = normalize(tag)
                if (normalized.isNotEmpty()) add(normalized)
            }
        }

    /**
     * Whether any of [itemTags] equals one of [hiddenTags] exactly, ignoring case. [hiddenTags]
     * must already be normalized.
     */
    fun matchesAny(
        itemTags: Iterable<String>,
        hiddenTags: Set<String>,
    ): Boolean {
        if (hiddenTags.isEmpty()) return false
        return itemTags.any { hiddenTags.contains(normalize(it)) }
    }
}
