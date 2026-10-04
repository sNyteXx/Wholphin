package com.github.damontecres.wholphin.custom.hiddenvault

import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.DisplayPreferencesDto
import java.util.UUID

/**
 * An in-memory Jellyfin behind the SDK's [ApiClient.request], so tests drive the real generated
 * APIs (`itemsApi`, `tvShowsApi`, …) through the vault's decorator.
 *
 * Items are named in tests ("a1", "s2e1") and get stable UUIDs. Behaviour that matters for the
 * vault is modelled after the Jellyfin server:
 * * `Tags` only come back when `fields` asks for them (single items always carry them)
 * * the `Tags` filter is OR over the requested tags and compares a cleaned form (punctuation
 *   dropped), which is looser than the vault's exact matching
 * * `ids` and `parentId` intersect
 */
class FakeJellyfin : ApiClient() {
    data class Call(
        val method: HttpMethod,
        val path: String,
        val params: Map<String, Any?>,
    ) {
        fun list(key: String): List<String>? = (params[key] as? Collection<*>)?.map { it.toString() }

        fun str(key: String): String? = params[key]?.toString()
    }

    class Item(
        val name: String,
        val library: String,
        val type: String,
        val tags: List<String> = emptyList(),
        val seriesName: String? = null,
        val seasonName: String? = null,
        val parentName: String? = null,
        val created: String = "2024-01-01T00:00:00Z",
        var nextUp: Boolean = false,
        var resume: Boolean = false,
        var played: Boolean = false,
        var favorite: Boolean = false,
        val people: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        var lastPlayed: String? = null,
    )

    val items = linkedMapOf<String, Item>()
    val libraries = linkedMapOf<String, String>() // name -> collection type
    val calls = mutableListOf<Call>()
    val displayPreferences = mutableMapOf<String, DisplayPreferencesDto>()
    var offline = false

    /** Libraries whose tag query fails (500) or that no longer exist (404) */
    val failingLibraries = mutableSetOf<String>()
    val deletedLibraries = mutableSetOf<String>()

    fun count(path: String) = calls.count { it.path == path }

    fun reset() = calls.clear()

    fun library(
        name: String,
        collectionType: String,
    ) {
        libraries[name] = collectionType
    }

    fun add(item: Item): Item {
        items[item.name] = item
        return item
    }

    fun series(
        library: String,
        name: String,
        vararg tags: String,
        created: String = "2024-01-01T00:00:00Z",
    ) = add(Item(name, library, "Series", tags.toList(), created = created))

    fun movie(
        library: String,
        name: String,
        vararg tags: String,
        created: String = "2024-01-01T00:00:00Z",
        resume: Boolean = false,
    ) = add(Item(name, library, "Movie", tags.toList(), created = created, resume = resume))

    fun boxSet(
        name: String,
        vararg tags: String,
    ) = add(Item(name, "lib-boxsets", "BoxSet", tags.toList()))

    fun season(
        library: String,
        name: String,
        series: String,
    ) = add(Item(name, library, "Season", seriesName = series, parentName = series))

    fun episode(
        library: String,
        name: String,
        series: String,
        season: String = "$series-s1",
        nextUp: Boolean = false,
        resume: Boolean = false,
        vararg tags: String,
    ) = add(
        Item(
            name,
            library,
            "Episode",
            tags.toList(),
            seriesName = series,
            seasonName = season,
            parentName = season,
            created = "2024-02-01T00:00:00Z",
            nextUp = nextUp,
            resume = resume,
        ),
    )

    // ---------------------------------------------------------------------------------------
    // ApiClient
    // ---------------------------------------------------------------------------------------

    override val baseUrl: String = "http://fake"
    override val accessToken: String = "token"
    override val clientInfo: ClientInfo = ClientInfo("test", "1")
    override val deviceInfo: DeviceInfo = DeviceInfo("device", "device")
    override val httpClientOptions: HttpClientOptions = HttpClientOptions()
    override val webSocket: SocketApi get() = throw UnsupportedOperationException()

    override fun update(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) = Unit

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse {
        val call = Call(method, pathTemplate, queryParameters + pathParameters.mapKeys { "path:${it.key}" })
        calls.add(call)
        val element: JsonElement? =
            when (method to pathTemplate) {
                HttpMethod.GET to "/Items" -> items(call)
                HttpMethod.GET to "/Items/{itemId}" -> single(pathParameters["itemId"])
                HttpMethod.GET to "/UserItems/Resume" -> page(call, flagged { it.resume }.filter { inLibrary(it, call.str("parentId")) })
                HttpMethod.GET to "/Shows/NextUp" -> nextUp(call)
                HttpMethod.GET to "/Items/Latest" -> latest(call)
                HttpMethod.GET to "/Items/{itemId}/Similar" -> similar(call, pathParameters["itemId"])
                HttpMethod.GET to "/Shows/{seriesId}/Episodes" -> episodes(call, pathParameters["seriesId"])
                HttpMethod.GET to "/Shows/{seriesId}/Seasons" -> seasons(call, pathParameters["seriesId"])
                HttpMethod.GET to "/Items/Filters" -> filters(call)
                HttpMethod.GET to "/UserViews" -> userViews()
                HttpMethod.GET to "/Items/{itemId}/SpecialFeatures" -> JsonArray(emptyList())
                HttpMethod.GET to "/DisplayPreferences/{displayPreferencesId}" -> getPrefs(call)
                HttpMethod.POST to "/DisplayPreferences/{displayPreferencesId}" -> savePrefs(call, requestBody)
                HttpMethod.POST to "/Items/{itemId}/PlaybackInfo" -> buildJsonObject { put("MediaSources", JsonArray(emptyList())) }
                HttpMethod.POST to "/Sessions/Playing/Progress" -> null
                else -> throw UnsupportedOperationException("Fake has no ${method.name} $pathTemplate")
            }
        val body = element?.let { json.encodeToString(JsonElement.serializer(), it).encodeToByteArray() } ?: ByteArray(0)
        return RawResponse(body, if (element == null) 204 else 200, emptyMap())
    }

    // ---------------------------------------------------------------------------------------
    // Endpoints
    // ---------------------------------------------------------------------------------------

    private fun items(call: Call): JsonElement {
        call.str("parentId")?.let { nameOf(it) }?.let { parent ->
            if (parent in deletedLibraries) throw InvalidStatusException(404)
            if (parent in failingLibraries && call.list("tags") != null) throw InvalidStatusException(500)
        }
        var result: List<Item> = items.values.toList()
        val ids = call.list("ids")?.map { normalize(it) }
        if (!ids.isNullOrEmpty()) result = result.filter { idOf(it.name) in ids }
        val parent = call.str("parentId")?.let { nameOf(it) }
        if (parent != null) {
            val recursive = call.params["recursive"] == true || !ids.isNullOrEmpty()
            result =
                result.filter { item ->
                    if (recursive) {
                        item.library == parent || item.seriesName == parent || item.seasonName == parent || item.parentName == parent
                    } else {
                        (item.library == parent && item.type in setOf("Series", "Movie", "BoxSet")) ||
                            item.parentName == parent
                    }
                }
        }
        call.list("includeItemTypes")?.takeIf { it.isNotEmpty() }?.let { types -> result = result.filter { it.type in types } }
        if (call.params["isFavorite"] == true) result = result.filter { it.favorite }
        if (call.list("filters")?.contains("IsUnplayed") == true) result = result.filter { !it.played }
        call.list("personIds")?.takeIf { it.isNotEmpty() }?.let { people ->
            val names = people.map { nameOf(it) }
            result = result.filter { item -> item.people.any { it in names } }
        }
        call.list("genres")?.takeIf { it.isNotEmpty() }?.let { genres ->
            result = result.filter { item -> item.genres.any { it in genres } }
        }
        call.list("tags")?.takeIf { it.isNotEmpty() }?.let { tags ->
            val wanted = tags.map { clean(it) }.toSet()
            result = result.filter { item -> item.tags.any { clean(it) in wanted } }
        }
        call.str("searchTerm")?.let { term -> result = result.filter { it.name.contains(term, ignoreCase = true) } }
        call.str("nameLessThan")?.let { bound -> result = result.filter { it.name < bound } }
        return page(call, sorted(call, result))
    }

    private fun sorted(
        call: Call,
        list: List<Item>,
    ): List<Item> {
        val sortBy = call.list("sortBy").orEmpty()
        return when {
            sortBy.firstOrNull() == "DateCreated" || sortBy.firstOrNull()?.startsWith("DateLastContentAdded") == true -> {
                list.sortedByDescending { it.created }
            }

            sortBy.firstOrNull() == "Random" -> {
                list.shuffled()
            }

            else -> {
                list
            }
        }
    }

    private fun page(
        call: Call,
        list: List<Item>,
    ): JsonElement {
        val start = (call.params["startIndex"] as? Int) ?: 0
        val limit = (call.params["limit"] as? Int)
        val slice = list.drop(start).let { if (limit != null) it.take(limit) else it }
        return buildJsonObject {
            put("Items", JsonArray(slice.map { toJson(it, tagsRequested(call)) }))
            put("TotalRecordCount", list.size)
            put("StartIndex", start)
        }
    }

    private fun single(id: Any?): JsonElement {
        val item = items.values.firstOrNull { idOf(it.name) == normalize(id.toString()) } ?: throw InvalidStatusException(404)
        return toJson(item, true)
    }

    private fun nextUp(call: Call): JsonElement {
        val series = call.str("seriesId")?.let { nameOf(it) }
        val parent = call.str("parentId")?.let { nameOf(it) }
        val list = flagged { it.nextUp }.filter { (series == null || it.seriesName == series) && (parent == null || it.library == parent) }
        return page(call, list)
    }

    private fun latest(call: Call): JsonElement {
        val parent = call.str("parentId")?.let { nameOf(it) }
        val limit = (call.params["limit"] as? Int) ?: 20
        val list =
            items.values
                .filter { it.type in setOf("Series", "Movie") && (parent == null || it.library == parent) }
                .sortedByDescending { it.created }
                .take(limit)
        return JsonArray(list.map { toJson(it, tagsRequested(call)) })
    }

    private fun similar(
        call: Call,
        id: Any?,
    ): JsonElement {
        val self = items.values.first { idOf(it.name) == normalize(id.toString()) }
        val limit = (call.params["limit"] as? Int) ?: 12
        val list = items.values.filter { it.type == self.type && it !== self }.take(limit)
        return buildJsonObject {
            put("Items", JsonArray(list.map { toJson(it, tagsRequested(call)) }))
            put("TotalRecordCount", list.size)
            put("StartIndex", 0)
        }
    }

    private fun episodes(
        call: Call,
        seriesId: Any?,
    ): JsonElement {
        val series = nameOf(seriesId.toString())
        return page(call, items.values.filter { it.type == "Episode" && it.seriesName == series })
    }

    private fun seasons(
        call: Call,
        seriesId: Any?,
    ): JsonElement {
        val series = nameOf(seriesId.toString())
        return page(call, items.values.filter { it.type == "Season" && it.seriesName == series })
    }

    private fun filters(call: Call): JsonElement {
        val parent = call.str("parentId")?.let { nameOf(it) }
        val tags =
            items.values
                .filter { parent == null || it.library == parent }
                .flatMap { it.tags }
                .distinct()
                .sorted()
        return buildJsonObject {
            put("Tags", JsonArray(tags.map { JsonPrimitive(it) }))
            put("Genres", JsonArray(emptyList()))
        }
    }

    private fun userViews(): JsonElement =
        buildJsonObject {
            put(
                "Items",
                JsonArray(
                    libraries.map { (name, type) ->
                        buildJsonObject {
                            put("Id", idOf(name))
                            put("Name", name)
                            put("Type", "CollectionFolder")
                            put("CollectionType", type)
                        }
                    },
                ),
            )
            put("TotalRecordCount", libraries.size)
            put("StartIndex", 0)
        }

    private fun prefsKey(call: Call) = "${call.str("client")}/${call.str("path:displayPreferencesId")}"

    private fun getPrefs(call: Call): JsonElement {
        if (offline) throw InvalidStatusException(503)
        val dto =
            displayPreferences[prefsKey(call)]
                ?: DisplayPreferencesDto(
                    id = call.str("path:displayPreferencesId"),
                    rememberIndexing = false,
                    primaryImageHeight = 0,
                    primaryImageWidth = 0,
                    customPrefs = emptyMap(),
                    scrollDirection = org.jellyfin.sdk.model.api.ScrollDirection.HORIZONTAL,
                    showBackdrop = false,
                    rememberSorting = false,
                    sortOrder = org.jellyfin.sdk.model.api.SortOrder.ASCENDING,
                    showSidebar = false,
                    client = call.str("client"),
                )
        return json.encodeToJsonElement(DisplayPreferencesDto.serializer(), dto)
    }

    private fun savePrefs(
        call: Call,
        body: Any?,
    ): JsonElement? {
        if (offline) throw InvalidStatusException(503)
        displayPreferences[prefsKey(call)] = body as DisplayPreferencesDto
        return null
    }

    // ---------------------------------------------------------------------------------------

    private fun flagged(predicate: (Item) -> Boolean) = items.values.filter(predicate)

    private fun inLibrary(
        item: Item,
        parentId: String?,
    ) = parentId == null || item.library == nameOf(parentId)

    private fun tagsRequested(call: Call) = call.list("fields")?.contains("Tags") == true

    fun toJson(
        item: Item,
        withTags: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("Name", item.name)
            put("Id", idOf(item.name))
            put("ServerId", "server-1")
            put("Type", item.type)
            put("DateCreated", item.created)
            put("IsFolder", item.type in setOf("Series", "Season", "BoxSet"))
            item.seriesName?.let {
                put("SeriesId", idOf(it))
                put("SeriesName", it)
            }
            item.seasonName?.let { put("SeasonId", idOf(it)) }
            put("ParentId", idOf(item.parentName ?: "folder-${item.library}"))
            if (withTags) put("Tags", JsonArray(item.tags.map { JsonPrimitive(it) }))
            put(
                "UserData",
                buildJsonObject {
                    put("PlaybackPositionTicks", 0)
                    put("PlayCount", if (item.played) 1 else 0)
                    put("IsFavorite", item.favorite)
                    put("Played", item.played)
                    put("Key", item.name)
                    put("ItemId", idOf(item.name))
                    item.lastPlayed?.let { put("LastPlayedDate", it) }
                },
            )
        }

    companion object {
        private val json = Json { encodeDefaults = false }

        /** The server's id spelling (no dashes) for a test name */
        fun idOf(name: String): String = ItemIds.of(uuidOf(name))!!

        fun uuidOf(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray()).also { names[ItemIds.of(it)!!] = name }

        private val names = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun nameOf(id: String): String {
            val normalized = normalize(id)
            return names[normalized] ?: normalized
        }

        fun normalize(id: String) = ItemIds.normalize(id)!!

        /** Jellyfin's GetCleanValue: lower case, punctuation to spaces, whitespace collapsed */
        fun clean(tag: String): String =
            tag
                .lowercase()
                .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
