package com.github.damontecres.wholphin.custom.hiddenvault.visibility

import org.jellyfin.sdk.api.client.HttpMethod

/**
 * How a response is shaped, which decides how items are taken out of it
 */
enum class ResponseShape {
    /** `{"Items": [...], "TotalRecordCount": n}` */
    QUERY_RESULT,

    /** `[ {...}, {...} ]` */
    ARRAY,

    /** One item: refused outright when hidden */
    SINGLE,

    /** `[ {"Items": [...]}, ... ]` (movie recommendations) */
    RECOMMENDATIONS,

    /** `{"SearchHints": [...]}` */
    SEARCH_HINTS,

    /** `{"Tags": [...]}` query filter values: hidden tags are not offered */
    QUERY_FILTERS,

    /** Starts playback of the item in the path: refused when hidden */
    PLAYBACK_INFO,

    /** Playback reports; let through, but count as vault activity */
    PLAYBACK_REPORT,
}

/**
 * Whether a hidden item may be shown in this call while its vault is entered
 */
enum class AllowanceKind {
    /** Global lists: never */
    NONE,

    /** The one item named in the path */
    SELF,

    /** Children of the anchor item (its seasons, episodes, parts) */
    CHILDREN,

    /** Items related to the anchor (similar, extras, instant mix), from the anchor's own vault */
    RELATED,
}

data class EndpointRule(
    val shape: ResponseShape,
    val allowance: AllowanceKind = AllowanceKind.NONE,
    /** Path parameter that names the anchor item, if any */
    val anchorPathParam: String? = null,
    /** The endpoint takes `fields`, so `Tags` can ride along with the normal request */
    val takesFields: Boolean = false,
    /** The endpoint takes `startIndex` and `limit` */
    val paged: Boolean = false,
    /** The endpoint takes `limit` only */
    val limited: Boolean = false,
)

/**
 * The whitelist of endpoints the vault looks at. Everything else passes through untouched, so
 * auth, sessions, images, display preferences, live TV, genres, persons and so on are never
 * touched. Path templates are the SDK's (Jellyfin Kotlin SDK 1.7).
 */
object EndpointCatalog {
    private val get: Map<String, EndpointRule> =
        buildMap {
            val list = EndpointRule(ResponseShape.QUERY_RESULT, takesFields = true, paged = true)
            put("/Items", list) // allowance decided per call (parentId)
            put("/UserItems/Resume", list)
            put("/Shows/NextUp", list) // allowance decided per call (seriesId)
            put("/Shows/Upcoming", list)
            put("/Items/Suggestions", list.copy(takesFields = false))
            put("/Trailers", list)
            put("/Playlists/{playlistId}/Items", list)
            put(
                "/Shows/{seriesId}/Episodes",
                list.copy(allowance = AllowanceKind.CHILDREN, anchorPathParam = "seriesId"),
            )
            put(
                "/Shows/{seriesId}/Seasons",
                EndpointRule(
                    ResponseShape.QUERY_RESULT,
                    AllowanceKind.CHILDREN,
                    "seriesId",
                    takesFields = true,
                ),
            )
            put(
                "/Items/Latest",
                EndpointRule(ResponseShape.ARRAY, takesFields = true, limited = true),
            )
            listOf(
                "/Items/{itemId}/Similar",
                "/Movies/{itemId}/Similar",
                "/Shows/{itemId}/Similar",
                "/Trailers/{itemId}/Similar",
                "/Albums/{itemId}/Similar",
                "/Artists/{itemId}/Similar",
            ).forEach {
                put(
                    it,
                    EndpointRule(
                        ResponseShape.QUERY_RESULT,
                        AllowanceKind.RELATED,
                        "itemId",
                        takesFields = true,
                        limited = true,
                    ),
                )
            }
            listOf(
                "/Items/{itemId}/InstantMix",
                "/Albums/{itemId}/InstantMix",
                "/Artists/{itemId}/InstantMix",
                "/Playlists/{itemId}/InstantMix",
                "/Songs/{itemId}/InstantMix",
            ).forEach {
                put(
                    it,
                    EndpointRule(
                        ResponseShape.QUERY_RESULT,
                        AllowanceKind.RELATED,
                        "itemId",
                        takesFields = true,
                        limited = true,
                    ),
                )
            }
            put(
                "/MusicGenres/{name}/InstantMix",
                EndpointRule(ResponseShape.QUERY_RESULT, takesFields = true, limited = true),
            )
            put(
                "/Artists/InstantMix",
                EndpointRule(ResponseShape.QUERY_RESULT, takesFields = true, limited = true),
            )
            put(
                "/MusicGenres/InstantMix",
                EndpointRule(ResponseShape.QUERY_RESULT, takesFields = true, limited = true),
            )
            put(
                "/Items/{itemId}/Intros",
                EndpointRule(ResponseShape.QUERY_RESULT, AllowanceKind.RELATED, "itemId"),
            )
            put(
                "/Videos/{itemId}/AdditionalParts",
                EndpointRule(ResponseShape.QUERY_RESULT, AllowanceKind.RELATED, "itemId"),
            )
            put(
                "/Items/{itemId}/SpecialFeatures",
                EndpointRule(ResponseShape.ARRAY, AllowanceKind.RELATED, "itemId"),
            )
            put(
                "/Items/{itemId}/LocalTrailers",
                EndpointRule(ResponseShape.ARRAY, AllowanceKind.RELATED, "itemId"),
            )
            put("/Items/{itemId}", EndpointRule(ResponseShape.SINGLE, AllowanceKind.SELF, "itemId"))
            put(
                "/Users/{userId}/Items/{itemId}",
                EndpointRule(ResponseShape.SINGLE, AllowanceKind.SELF, "itemId"),
            )
            put("/Movies/Recommendations", EndpointRule(ResponseShape.RECOMMENDATIONS, takesFields = true))
            put("/Search/Hints", EndpointRule(ResponseShape.SEARCH_HINTS, limited = true))
            put("/Items/Filters", EndpointRule(ResponseShape.QUERY_FILTERS))
            put("/Items/Filters2", EndpointRule(ResponseShape.QUERY_FILTERS))
            put(
                "/Items/{itemId}/PlaybackInfo",
                EndpointRule(ResponseShape.PLAYBACK_INFO, AllowanceKind.SELF, "itemId"),
            )
        }

    private val post: Map<String, EndpointRule> =
        mapOf(
            "/Items/{itemId}/PlaybackInfo" to
                EndpointRule(ResponseShape.PLAYBACK_INFO, AllowanceKind.SELF, "itemId"),
            "/Sessions/Playing" to EndpointRule(ResponseShape.PLAYBACK_REPORT),
            "/Sessions/Playing/Progress" to EndpointRule(ResponseShape.PLAYBACK_REPORT),
        )

    fun classify(
        method: HttpMethod,
        pathTemplate: String,
    ): EndpointRule? =
        when (method) {
            HttpMethod.GET -> get[pathTemplate]
            HttpMethod.POST -> post[pathTemplate]
            else -> null
        }
}
