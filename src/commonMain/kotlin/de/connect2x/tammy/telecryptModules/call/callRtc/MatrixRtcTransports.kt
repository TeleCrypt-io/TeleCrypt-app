package de.connect2x.tammy.telecryptModules.call.callRtc

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.folivo.trixnity.client.MatrixClient

data class MatrixRtcTransport(
    val type: String,
    val uri: String? = null,
    val params: JsonObject = JsonObject(emptyMap()),
)

suspend fun discoverRtcTransports(matrixClient: MatrixClient): List<MatrixRtcTransport> {
    val client = matrixClient.api.baseClient.baseClient
    val baseUrl = matrixClient.api.baseClient.baseUrl ?: return emptyList()
    val json = matrixClient.api.json
    val stable = fetchRtcTransports(client, baseUrl, json, STABLE_PATH)
    if (stable.isNotEmpty()) {
        return stable
    }
    return fetchRtcTransports(client, baseUrl, json, UNSTABLE_PATH)
}

private suspend fun fetchRtcTransports(
    client: HttpClient,
    baseUrl: Url,
    json: Json,
    path: String,
): List<MatrixRtcTransport> {
    val url = buildUrl(baseUrl, path)
    val body = runCatching { client.get(url).bodyAsText() }.getOrNull() ?: return emptyList()
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: return emptyList()
    val transports = root["transports"] as? JsonArray ?: return emptyList()
    return transports.mapNotNull { parseTransport(it) }
}

private fun parseTransport(element: JsonElement): MatrixRtcTransport? {
    val obj = element as? JsonObject ?: return null
    val type = obj.string("type") ?: return null
    val uri = obj.string("uri")
    val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
    return MatrixRtcTransport(type = type, uri = uri, params = params)
}

private fun buildUrl(baseUrl: Url, path: String): String {
    val normalized = if (path.startsWith("/")) path else "/$path"
    return baseUrl.toString().trimEnd('/') + normalized
}

private fun JsonObject.string(key: String): String? = get(key).asString()

private fun JsonElement?.asString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.content.takeIf { it.isNotBlank() }
}

private const val STABLE_PATH = "/_matrix/client/v1/rtc/transports"
private const val UNSTABLE_PATH = "/_matrix/client/unstable/org.matrix.msc4143/rtc/transports"
