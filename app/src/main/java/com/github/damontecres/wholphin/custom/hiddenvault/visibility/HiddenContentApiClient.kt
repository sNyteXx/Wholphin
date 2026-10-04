package com.github.damontecres.wholphin.custom.hiddenvault.visibility

import com.github.damontecres.wholphin.custom.hiddenvault.data.HiddenContentService
import com.github.damontecres.wholphin.custom.hiddenvault.model.ItemIds
import com.github.damontecres.wholphin.custom.hiddenvault.model.TagMatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * The app's [ApiClient], wrapped so the normal app never sees hidden content.
 *
 * Every Jellyfin call of the app goes through [request], which is what makes this the one place
 * hidden content is kept out: home rows, continue watching, next up, latest, search, library
 * grids, favorites, collections, genres, similar items, extras, the screensaver, launcher channels,
 * playback queues and direct item ids alike – including screens added upstream later.
 *
 * Only a whitelist of item endpoints ([EndpointCatalog]) is looked at, and for those only two
 * things ever change: items are taken out of `Items`/arrays and `TotalRecordCount` is lowered.
 * Single items and playback of a hidden item are refused like an item that doesn't exist (404).
 * With no rules configured nothing is parsed and every call goes straight through.
 *
 * [raw] is the unfiltered client. Only the vault's own code (index build, vault screens, exact
 * paging) may use it.
 */
class HiddenContentApiClient(
    val raw: ApiClient,
    private val runtimeProvider: () -> HiddenVaultRuntime?,
    private val processingContext: CoroutineContext = Dispatchers.Default,
) : ApiClient() {
    override val baseUrl: String?
        get() = raw.baseUrl
    override val accessToken: String?
        get() = raw.accessToken
    override val clientInfo: ClientInfo
        get() = raw.clientInfo
    override val deviceInfo: DeviceInfo
        get() = raw.deviceInfo
    override val httpClientOptions: HttpClientOptions
        get() = raw.httpClientOptions
    override val webSocket: SocketApi
        get() = raw.webSocket

    override fun update(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) {
        raw.update(baseUrl, accessToken, clientInfo, deviceInfo)
    }

    /** Items seen recently, so the playback gate rarely needs a lookup of its own */
    private val recent = RecentItems(RECENT_CAPACITY)

    /** Hidden items recently let through inside their vault, by id */
    private val allowedRecently = RecentItems(RECENT_CAPACITY)

    /** Requests sent by read-ahead and hidden counts, for the tests and the log */
    @Volatile
    var extraRequests = 0
        private set

    val runtime: HiddenVaultRuntime? get() = runtimeProvider()

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse {
        val rule =
            EndpointCatalog.classify(method, pathTemplate)
                ?: return raw.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
        val runtime =
            runtimeProvider()
                ?: return raw.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
        val call = Call(method, pathTemplate, pathParameters, queryParameters, requestBody)
        return withContext(processingContext) {
            if (rule.shape == ResponseShape.PLAYBACK_REPORT) {
                noteReport(runtime, requestBody)
                return@withContext raw.request(method, pathTemplate, pathParameters, queryParameters, requestBody)
            }
            val service = runtime.activeService() ?: return@withContext call.send(queryParameters)
            when (rule.shape) {
                ResponseShape.SINGLE -> single(call, rule, service, runtime)
                ResponseShape.PLAYBACK_INFO -> playbackInfo(call, rule, service, runtime)
                ResponseShape.QUERY_FILTERS -> queryFilters(call, service)
                ResponseShape.RECOMMENDATIONS -> recommendations(call, rule, service)
                ResponseShape.SEARCH_HINTS -> searchHints(call, service)
                ResponseShape.QUERY_RESULT, ResponseShape.ARRAY -> list(call, rule, service, runtime)
                ResponseShape.PLAYBACK_REPORT -> call.send(queryParameters)
            }
        }
    }

    /**
     * Whether an item may be played right now: hidden items only inside their own open vault.
     * Cheap when the item was seen recently; otherwise one lookup over the unfiltered client.
     */
    suspend fun refusesPlayback(itemId: UUID): Boolean {
        val runtime = runtimeProvider() ?: return false
        return withContext(processingContext) {
            val service = runtime.activeService() ?: return@withContext false
            val id = ItemIds.of(itemId) ?: return@withContext false
            val ref = recent[id] ?: lookup(itemId)
            refused(ref, service, runtime)
        }
    }

    /** Same as [refusesPlayback] for an item already in hand */
    suspend fun refusesPlayback(ref: ItemRef): Boolean {
        val runtime = runtimeProvider() ?: return false
        return withContext(processingContext) {
            val service = runtime.activeService() ?: return@withContext false
            refused(ref, service, runtime)
        }
    }

    private suspend fun refused(
        ref: ItemRef,
        service: HiddenContentService,
        runtime: HiddenVaultRuntime,
    ): Boolean {
        val verdict = service.settle(listOf(ref)).single()
        if (verdict !is Verdict.Hidden) return false
        val allowance = VaultAllowance.of(AllowanceKind.SELF, ref.id, service, runtime)
        return !allowance.allows(ref, verdict.vaultId)
    }

    private suspend fun lookup(itemId: UUID): ItemRef {
        extraRequests++
        val ref = ItemRef.of(raw.userLibraryApi.getItem(itemId).content)
        recent.put(ref)
        return ref
    }

    private fun noteReport(
        runtime: HiddenVaultRuntime,
        body: Any?,
    ) {
        val itemId =
            when (body) {
                is PlaybackProgressInfo -> body.itemId
                is PlaybackStartInfo -> body.itemId
                else -> null
            }
        val vaultId = allowedRecently.vaultOf(ItemIds.of(itemId)) ?: return
        runtime.noteVaultActivity(vaultId)
    }

    // ---------------------------------------------------------------------------------------
    // Single items and playback
    // ---------------------------------------------------------------------------------------

    private suspend fun single(
        call: Call,
        rule: EndpointRule,
        service: HiddenContentService,
        runtime: HiddenVaultRuntime,
    ): RawResponse {
        val itemId = ItemIds.normalize(call.pathParameters[rule.anchorPathParam]?.toString())
        val allowance = VaultAllowance.of(AllowanceKind.SELF, itemId, service, runtime)
        // Known from the index: refuse without asking the server for anything
        service.vaultOfId(itemId)?.let { vaultId ->
            if (!allowance.allows(ItemRef(itemId), vaultId)) throw refusal()
        }
        val response = call.send(call.queryParameters)
        val json = parse(response) as? JsonObject ?: return response
        val ref = ItemRef.of(json)
        recent.put(ref)
        val verdict = service.settle(listOf(ref)).single()
        if (verdict !is Verdict.Hidden) return response
        if (allowance.allows(ref, verdict.vaultId)) {
            allowedRecently.put(ref, verdict.vaultId)
            return response
        }
        throw refusal()
    }

    private suspend fun playbackInfo(
        call: Call,
        rule: EndpointRule,
        service: HiddenContentService,
        runtime: HiddenVaultRuntime,
    ): RawResponse {
        val itemId = ItemIds.toUuid(call.pathParameters[rule.anchorPathParam]?.toString())
        if (itemId != null) {
            val ref = recent[ItemIds.of(itemId)] ?: lookup(itemId)
            if (refused(ref, service, runtime)) throw refusal()
        }
        return call.send(call.queryParameters)
    }

    // ---------------------------------------------------------------------------------------
    // Lists
    // ---------------------------------------------------------------------------------------

    private suspend fun list(
        call: Call,
        rule: EndpointRule,
        service: HiddenContentService,
        runtime: HiddenVaultRuntime,
    ): RawResponse {
        val params = call.queryParameters
        val (kind, anchorId) = allowanceFor(call, rule, service)
        val allowance = VaultAllowance.of(kind, anchorId, service, runtime)
        val query = if (rule.takesFields) withTags(params) else params
        val limit = (params["limit"] as? Number)?.toInt()
        val start = (params["startIndex"] as? Number)?.toInt()

        if (limit == 0 && call.pathTemplate == "/Items" && kind == AllowanceKind.NONE) {
            return countOnly(call, query, service)
        }

        val first = call.send(query)
        val firstPage = ItemPage.parse(parse(first), rule.shape) ?: return first
        val firstKept = keep(firstPage.items, service, allowance)
        if (firstKept.size == firstPage.items.size) return first

        val visible = firstKept.toMutableList()
        val seen = visible.mapNotNullTo(mutableSetOf()) { ItemIds.normalize(it.idString()) }
        var rawConsumed = firstPage.items.size
        var hiddenSeen = firstPage.items.size - firstKept.size
        var lastRawSize = firstPage.items.size
        var requestLimit = limit ?: 0
        var exhausted = limit == null || firstPage.items.size < (limit)
        val topN = limit != null && limit > 0 && (start == null || start == 0) && (rule.paged || rule.limited)
        var extra = 0
        // A row three quarters full looks full; only thin rows are worth another request
        val target = if (limit != null) (minOf(limit, FULL_ROW_TARGET) * 3 + 3) / 4 else 0
        while (topN && !exhausted && visible.size < target && extra < MAX_EXTRA_REQUESTS) {
            extra++
            extraRequests++
            if (rule.paged) {
                requestLimit = (requestLimit * 2).coerceAtMost(maxOf(limit, MAX_READ_AHEAD_PAGE))
                val next =
                    ItemPage.parse(
                        parse(call.send(query + mapOf("startIndex" to rawConsumed, "limit" to requestLimit))),
                        rule.shape,
                    ) ?: break
                val kept = keep(next.items, service, allowance)
                rawConsumed += next.items.size
                hiddenSeen += next.items.size - kept.size
                kept.forEach { item ->
                    if (visible.size < limit!! && seen.add(ItemIds.normalize(item.idString()) ?: "")) visible.add(item)
                }
                exhausted = next.items.size < requestLimit
            } else {
                // Takes no start index: read again from the top with a bigger window
                if (requestLimit >= MAX_READ_AHEAD_PAGE) break
                requestLimit = (requestLimit * 3).coerceAtMost(MAX_READ_AHEAD_PAGE)
                val next =
                    ItemPage.parse(parse(call.send(query + mapOf("limit" to requestLimit))), rule.shape)
                        ?: break
                val kept = keep(next.items, service, allowance)
                visible.clear()
                seen.clear()
                kept.forEach { item ->
                    if (visible.size < limit!! && seen.add(ItemIds.normalize(item.idString()) ?: "")) visible.add(item)
                }
                hiddenSeen = next.items.size - kept.size
                rawConsumed = next.items.size
                lastRawSize = next.items.size
                exhausted = lastRawSize < requestLimit
            }
        }

        val rawTotal = firstPage.totalRecordCount
        val total =
            when {
                rawTotal == null -> null
                exhausted && topN -> (start ?: 0) + visible.size
                else -> (rawTotal - hiddenSeen).coerceAtLeast((start ?: 0) + visible.size).coerceAtMost(rawTotal)
            }
        return first.withBody(firstPage.rebuild(visible, total))
    }

    /**
     * The allowance a list call gets: only lists about one particular item. A library is never an
     * anchor, so vault titles never leak into a library list, even inside the vault.
     */
    private fun allowanceFor(
        call: Call,
        rule: EndpointRule,
        service: HiddenContentService,
    ): Pair<AllowanceKind, String?> {
        rule.anchorPathParam?.let { param ->
            return rule.allowance to ItemIds.normalize(call.pathParameters[param]?.toString())
        }
        return when (call.pathTemplate) {
            "/Items" -> {
                val ids = call.queryParameters["ids"] as? Collection<*>
                val parent = ItemIds.normalize(call.queryParameters["parentId"]?.toString())
                if (parent == null || !ids.isNullOrEmpty() || isLibrary(parent, service)) {
                    AllowanceKind.NONE to null
                } else {
                    AllowanceKind.CHILDREN to parent
                }
            }

            "/Shows/NextUp" -> {
                val series = ItemIds.normalize(call.queryParameters["seriesId"]?.toString())
                if (series == null) AllowanceKind.NONE to null else AllowanceKind.CHILDREN to series
            }

            else -> {
                AllowanceKind.NONE to null
            }
        }
    }

    private fun isLibrary(
        id: String,
        service: HiddenContentService,
    ): Boolean = service.policy.ruleFor(id) != null || service.index?.knownLibraryIds?.contains(id) == true

    private suspend fun keep(
        items: List<JsonObject>,
        service: HiddenContentService,
        allowance: VaultAllowance,
    ): List<JsonObject> {
        if (items.isEmpty()) return items
        val refs = items.map { ItemRef.of(it) }
        refs.forEach { recent.put(it) }
        val verdicts = service.settle(refs)
        return items.filterIndexed { i, _ ->
            when (val verdict = verdicts[i]) {
                Verdict.Visible -> {
                    true
                }

                is Verdict.Hidden -> {
                    allowance.allows(refs[i], verdict.vaultId).also { allowed ->
                        if (allowed) allowedRecently.put(refs[i], verdict.vaultId)
                    }
                }
            }
        }
    }

    /**
     * A count only request (`limit=0`, used to jump to a letter). The hidden items among the
     * results are counted with the same query restricted to the indexed ids, so the position
     * matches the filtered list.
     */
    private suspend fun countOnly(
        call: Call,
        query: Map<String, Any?>,
        service: HiddenContentService,
    ): RawResponse {
        val response = call.send(query)
        val json = parse(response) as? JsonObject ?: return response
        val rawTotal = (json["TotalRecordCount"] as? JsonPrimitive)?.intOrNull ?: return response
        if (!(call.queryParameters["ids"] as? Collection<*>).isNullOrEmpty()) return response
        val index = service.index ?: return response
        val parent = ItemIds.normalize(call.queryParameters["parentId"]?.toString())
        val candidates =
            when {
                parent == null -> index.idsOf()
                service.policy.ruleFor(parent) != null -> index.idsOf(libraryId = parent)
                index.knownLibraryIds.contains(parent) -> emptyList()
                else -> index.idsOf()
            }
        if (candidates.isEmpty()) return response
        var hidden = 0
        candidates.chunked(COUNT_CHUNK).forEach { chunk ->
            extraRequests++
            val counted =
                parse(
                    call.send(
                        query +
                            mapOf(
                                "ids" to chunk.mapNotNull { ItemIds.toUuid(it) },
                                "startIndex" to null,
                                "limit" to 0,
                                "enableTotalRecordCount" to true,
                            ),
                    ),
                ) as? JsonObject
            hidden += (counted?.get("TotalRecordCount") as? JsonPrimitive)?.intOrNull ?: 0
        }
        if (hidden == 0) return response
        return response.withBody(JsonObject(json + ("TotalRecordCount" to JsonPrimitive((rawTotal - hidden).coerceAtLeast(0)))))
    }

    private suspend fun queryFilters(
        call: Call,
        service: HiddenContentService,
    ): RawResponse {
        val response = call.send(call.queryParameters)
        val json = parse(response) as? JsonObject ?: return response
        val tags = json["Tags"] as? JsonArray ?: return response
        val parent = ItemIds.normalize(call.queryParameters["parentId"]?.toString())
        val hiddenTags = service.policy.tagsForScope(parent?.takeIf { service.policy.ruleFor(it) != null })
        val kept =
            tags.filter {
                val tag = (it as? JsonPrimitive)?.contentOrNull ?: return@filter true
                !hiddenTags.contains(TagMatch.normalize(tag))
            }
        if (kept.size == tags.size) return response
        return response.withBody(JsonObject(json + ("Tags" to JsonArray(kept))))
    }

    private suspend fun recommendations(
        call: Call,
        rule: EndpointRule,
        service: HiddenContentService,
    ): RawResponse {
        val query = if (rule.takesFields) withTags(call.queryParameters) else call.queryParameters
        val response = call.send(query)
        val groups = parse(response) as? JsonArray ?: return response
        var changed = false
        val filtered =
            groups.map { group ->
                val obj = group as? JsonObject ?: return@map group
                val items = (obj["Items"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return@map group
                val kept = keep(items, service, VaultAllowance.NONE)
                if (kept.size == items.size) return@map group
                changed = true
                JsonObject(obj + ("Items" to JsonArray(kept)))
            }
        return if (changed) response.withBody(JsonArray(filtered)) else response
    }

    private suspend fun searchHints(
        call: Call,
        service: HiddenContentService,
    ): RawResponse {
        val response = call.send(call.queryParameters)
        val json = parse(response) as? JsonObject ?: return response
        val hints = (json["SearchHints"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return response
        // Hints carry no series id; their thumb and backdrop usually come from the series
        val refs =
            hints.map {
                ItemRef(
                    id = ItemIds.normalize(it.str("ItemId") ?: it.str("Id")),
                    type = it.str("Type"),
                    seriesId = ItemIds.normalize(it.str("ThumbImageItemId")),
                    parentId = ItemIds.normalize(it.str("BackdropImageItemId")),
                    albumId = ItemIds.normalize(it.str("AlbumId")),
                )
            }
        val verdicts = service.settle(refs)
        val kept = hints.filterIndexed { i, _ -> !verdicts[i].isHidden }
        if (kept.size == hints.size) return response
        val total = (json["TotalRecordCount"] as? JsonPrimitive)?.intOrNull
        return response.withBody(
            JsonObject(
                json + ("SearchHints" to JsonArray(kept)) +
                    (total?.let { mapOf("TotalRecordCount" to JsonPrimitive(it - (hints.size - kept.size))) } ?: emptyMap()),
            ),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    private inner class Call(
        val method: HttpMethod,
        val pathTemplate: String,
        val pathParameters: Map<String, Any?>,
        val queryParameters: Map<String, Any?>,
        val requestBody: Any?,
    ) {
        suspend fun send(query: Map<String, Any?>): RawResponse = raw.request(method, pathTemplate, pathParameters, query, requestBody)
    }

    private fun withTags(params: Map<String, Any?>): Map<String, Any?> {
        val fields = params["fields"] as? Collection<*>
        if (fields != null && fields.contains(ItemFields.TAGS)) return params
        return params + ("fields" to (fields.orEmpty() + ItemFields.TAGS))
    }

    private fun parse(response: RawResponse): JsonElement? {
        if (response.status !in 200..299 || response.body.isEmpty()) return null
        return try {
            json.parseToJsonElement(response.body.decodeToString())
        } catch (_: Exception) {
            null
        }
    }

    private fun RawResponse.withBody(element: JsonElement): RawResponse =
        RawResponse(json.encodeToString(JsonElement.serializer(), element).encodeToByteArray(), status, headers)

    private fun refusal() = InvalidStatusException(404)

    /** The items of one list response, and how to put a filtered list back */
    private class ItemPage(
        private val original: JsonElement,
        val items: List<JsonObject>,
        val totalRecordCount: Int?,
    ) {
        fun rebuild(
            items: List<JsonObject>,
            total: Int?,
        ): JsonElement =
            when (original) {
                is JsonObject -> {
                    JsonObject(
                        original + ("Items" to JsonArray(items)) +
                            (total?.let { mapOf("TotalRecordCount" to JsonPrimitive(it)) } ?: emptyMap()),
                    )
                }

                else -> {
                    JsonArray(items)
                }
            }

        companion object {
            fun parse(
                element: JsonElement?,
                shape: ResponseShape,
            ): ItemPage? =
                when {
                    element == null -> {
                        null
                    }

                    shape == ResponseShape.ARRAY && element is JsonArray -> {
                        ItemPage(element, element.filterIsInstance<JsonObject>(), null)
                    }

                    element is JsonObject && element["Items"] is JsonArray -> {
                        ItemPage(
                            element,
                            (element["Items"] as JsonArray).filterIsInstance<JsonObject>(),
                            (element["TotalRecordCount"] as? JsonPrimitive)?.intOrNull,
                        )
                    }

                    else -> {
                        null
                    }
                }
        }
    }

    companion object {
        /** Extra requests one top-N list may cost on top of the first */
        const val MAX_EXTRA_REQUESTS = 3

        /** Largest page asked for while reading ahead */
        const val MAX_READ_AHEAD_PAGE = 200

        /**
         * Reading ahead stops once a list holds this many items (or its whole limit, when
         * smaller). A home row shows about fifteen; nobody needs every slot of a hundred refilled.
         */
        const val FULL_ROW_TARGET = 24

        const val COUNT_CHUNK = 100
        const val RECENT_CAPACITY = 2048

        internal val json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        internal fun JsonObject.idString(): String? = str("Id")
    }
}

/**
 * A small bounded map from item id to what was last seen of it
 */
internal class RecentItems(
    private val capacity: Int,
) {
    private val refs =
        object : LinkedHashMap<String, Pair<ItemRef, String?>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<ItemRef, String?>>?): Boolean = size > capacity
        }

    @Synchronized
    fun put(
        ref: ItemRef,
        vaultId: String? = null,
    ) {
        val id = ref.id ?: return
        refs[id] = ref to vaultId
    }

    @Synchronized
    operator fun get(id: String?): ItemRef? = id?.let { refs[it]?.first }

    @Synchronized
    fun vaultOf(id: String?): String? = id?.let { refs[it]?.second }
}
