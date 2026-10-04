package com.github.damontecres.wholphin.custom.hiddenvault.model

/**
 * What the tag picker lists and how picking changes the selection, kept apart from the screen so
 * it can be tested without one.
 *
 * The list order never depends on the selection, so ticking a tag never moves any row and the
 * D-pad focus stays where it is.
 */
object TagPickerModel {
    /**
     * Configured tags the server doesn't list (typed by hand, or gone from the server). Once in
     * the list they stay for the whole visit, even when unticked.
     */
    fun extras(
        selected: List<String>,
        serverTags: List<String>,
        current: List<String>,
    ): List<String> {
        val known = serverTags.mapTo(HashSet()) { TagMatch.normalize(it) }
        val result = current.toMutableList()
        val seen = current.mapTo(HashSet()) { TagMatch.normalize(it) }
        selected.forEach { tag ->
            val normalized = TagMatch.normalize(tag)
            if (normalized !in known && seen.add(normalized)) result.add(tag)
        }
        return result
    }

    /** The rows to show: extras first, then the server's tags, narrowed by [filter] */
    fun rows(
        serverTags: List<String>,
        extras: List<String>,
        filter: String,
    ): List<String> {
        val all = extras + serverTags
        val needle = filter.trim().lowercase()
        return if (needle.isEmpty()) all else all.filter { it.lowercase().contains(needle) }
    }

    /** Ticks or unticks [tag], keeping the stored spelling of everything else */
    fun toggle(
        selected: List<String>,
        tag: String,
    ): List<String> {
        val normalized = TagMatch.normalize(tag)
        if (normalized.isEmpty()) return selected
        return if (selected.any { TagMatch.normalize(it) == normalized }) {
            selected.filter { TagMatch.normalize(it) != normalized }
        } else {
            selected + tag.trim()
        }
    }

    /** Adds a typed tag unless it is already selected (in any spelling) */
    fun add(
        selected: List<String>,
        tag: String,
    ): List<String> {
        val normalized = TagMatch.normalize(tag)
        if (normalized.isEmpty() || selected.any { TagMatch.normalize(it) == normalized }) return selected
        return selected + tag.trim()
    }
}
