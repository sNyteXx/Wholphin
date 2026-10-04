package com.github.damontecres.wholphin.custom.hiddenvault.visibility

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ItemFields

/**
 * An unfiltered client that adjusts the query of item list requests before they go out.
 *
 * Lets the exact paging run any upstream [com.github.damontecres.wholphin.util.RequestHandler]
 * unchanged while asking for the `Tags` field, or for a lightweight id-only "skeleton" of the
 * same query.
 */
internal class QueryRewritingApiClient(
    private val inner: ApiClient,
    private val rewrite: (pathTemplate: String, query: Map<String, Any?>) -> Map<String, Any?>,
) : ApiClient() {
    override val baseUrl: String?
        get() = inner.baseUrl
    override val accessToken: String?
        get() = inner.accessToken
    override val clientInfo: ClientInfo
        get() = inner.clientInfo
    override val deviceInfo: DeviceInfo
        get() = inner.deviceInfo
    override val httpClientOptions: HttpClientOptions
        get() = inner.httpClientOptions
    override val webSocket: SocketApi
        get() = inner.webSocket

    override fun update(
        baseUrl: String?,
        accessToken: String?,
        clientInfo: ClientInfo,
        deviceInfo: DeviceInfo,
    ) {
        inner.update(baseUrl, accessToken, clientInfo, deviceInfo)
    }

    override suspend fun request(
        method: HttpMethod,
        pathTemplate: String,
        pathParameters: Map<String, Any?>,
        queryParameters: Map<String, Any?>,
        requestBody: Any?,
    ): RawResponse {
        val takesFields = EndpointCatalog.classify(method, pathTemplate)?.takesFields == true
        val query = if (takesFields) rewrite(pathTemplate, queryParameters) else queryParameters
        return inner.request(method, pathTemplate, pathParameters, query, requestBody)
    }

    companion object {
        /** The normal request plus the `Tags` field */
        fun withTags(inner: ApiClient) =
            QueryRewritingApiClient(inner) { _, query ->
                val fields = query["fields"] as? Collection<*>
                if (fields != null && fields.contains(ItemFields.TAGS)) {
                    query
                } else {
                    query + ("fields" to (fields.orEmpty() + ItemFields.TAGS))
                }
            }

        /**
         * Only what visibility needs: ids, parent ids and tags. No images, no user data, no
         * total count work beyond what the caller asked for.
         */
        fun skeleton(inner: ApiClient) =
            QueryRewritingApiClient(inner) { _, query ->
                query +
                    mapOf(
                        "fields" to listOf(ItemFields.TAGS),
                        "enableImages" to false,
                        "enableImageTypes" to null,
                        "imageTypeLimit" to 0,
                        "enableUserData" to false,
                    )
            }
    }
}
