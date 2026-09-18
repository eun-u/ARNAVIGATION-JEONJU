package kr.co.navi.mobility.data.remote

import java.net.URLEncoder
import kr.co.navi.mobility.data.model.GraphResponseDto
import kr.co.navi.mobility.data.model.GraphEnrichmentCandidateListDto
import kr.co.navi.mobility.data.model.GraphEnrichmentSimulationRequestDto
import kr.co.navi.mobility.data.model.GraphEnrichmentSimulationResponseDto
import kr.co.navi.mobility.data.model.GraphEnrichmentSummaryDto
import kr.co.navi.mobility.data.model.ObservationCandidateCreateDto
import kr.co.navi.mobility.data.model.ObservationCandidateDto
import kr.co.navi.mobility.data.model.RouteComparisonDto
import kr.co.navi.mobility.data.model.RouteRequestDto
import kr.co.navi.mobility.data.model.SessionRerouteRequestDto
import kr.co.navi.mobility.data.model.SessionRerouteResponseDto
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NaviApiClient(
    baseUrl: String,
    private val transport: HttpTransport = UrlConnectionHttpTransport(),
    private val json: Json = defaultJson,
    private val accessToken: String = "",
) {
    private val baseUrl = baseUrl.trim().trimEnd('/').also {
        require(it.startsWith("http://") || it.startsWith("https://")) {
            "NaVi backend URL must use http or https"
        }
    }

    suspend fun getGraph(): GraphResponseDto = get("/graph", GraphResponseDto.serializer())

    suspend fun getJeonjuBootstrap(): JsonObject = get("/demo/jeonju", JsonObject.serializer())

    suspend fun startJeonjuRoute(request: RouteRequestDto): kr.co.navi.mobility.data.model.RouteResultDto = post(
        "/route", request, RouteRequestDto.serializer(), kr.co.navi.mobility.data.model.RouteResultDto.serializer(),
    )

    suspend fun rerouteJeonju(sessionId: String, payload: JsonObject): JsonObject = post(
        "/route/sessions/${encode(sessionId)}/reroute", payload, JsonObject.serializer(), JsonObject.serializer(),
    )

    suspend fun compareRoute(request: RouteRequestDto): RouteComparisonDto = post(
        path = "/route/compare",
        body = request,
        serializer = RouteRequestDto.serializer(),
        deserializer = RouteComparisonDto.serializer(),
    )

    suspend fun reroute(
        sessionId: String,
        request: SessionRerouteRequestDto,
    ): SessionRerouteResponseDto = post(
        path = "/route/sessions/${encode(sessionId)}/reroute",
        body = request,
        serializer = SessionRerouteRequestDto.serializer(),
        deserializer = SessionRerouteResponseDto.serializer(),
    )

    suspend fun createCandidate(
        request: ObservationCandidateCreateDto,
    ): ObservationCandidateDto = post(
        path = "/observations/candidates",
        body = request,
        serializer = ObservationCandidateCreateDto.serializer(),
        deserializer = ObservationCandidateDto.serializer(),
    )

    suspend fun getGraphEnrichmentSummary(): GraphEnrichmentSummaryDto = get(
        "/graph-enrichment/summary",
        GraphEnrichmentSummaryDto.serializer(),
    )

    suspend fun getGraphCandidates(
        routeAffecting: Boolean,
    ): GraphEnrichmentCandidateListDto = get(
        "/graph-enrichment/candidates?route_affecting=$routeAffecting&limit=250",
        GraphEnrichmentCandidateListDto.serializer(),
    )

    suspend fun simulateGraphCandidate(
        request: GraphEnrichmentSimulationRequestDto,
    ): GraphEnrichmentSimulationResponseDto = post(
        path = "/graph-enrichment/simulate",
        body = request,
        serializer = GraphEnrichmentSimulationRequestDto.serializer(),
        deserializer = GraphEnrichmentSimulationResponseDto.serializer(),
    )

    private suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>): T =
        request(
            HttpRequest(
                method = HttpMethod.GET,
                url = "$baseUrl$path",
                headers = mapOf("Accept" to "application/json") + authHeaders(),
            ),
            deserializer,
        )

    private suspend fun <B, T> post(
        path: String,
        body: B,
        serializer: SerializationStrategy<B>,
        deserializer: DeserializationStrategy<T>,
    ): T = request(
        HttpRequest(
            method = HttpMethod.POST,
            url = "$baseUrl$path",
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json; charset=utf-8",
            ) + authHeaders(),
            body = json.encodeToString(serializer, body),
        ),
        deserializer,
    )

    private fun authHeaders(): Map<String, String> =
        if (accessToken.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $accessToken")

    private suspend fun <T> request(
        request: HttpRequest,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val response = transport.execute(request)
        if (response.statusCode !in 200..299) {
            val (code, message) = parseError(response.body)
            throw NaviApiException(
                statusCode = response.statusCode,
                errorCode = code,
                message = message ?: "NaVi 서버 요청에 실패했습니다. (${response.statusCode})",
            )
        }
        if (response.body.isBlank()) throw NaviProtocolException("NaVi 서버가 빈 응답을 반환했습니다.")
        return try {
            json.decodeFromString(deserializer, response.body)
        } catch (error: Exception) {
            throw NaviProtocolException("NaVi 서버 응답 형식이 앱 계약과 다릅니다.", error)
        }
    }

    private fun parseError(body: String): Pair<String?, String?> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull
        val reason = root["reason_code"]?.jsonPrimitive?.contentOrNull
        val message = root["message"]?.jsonPrimitive?.contentOrNull
        val detail = root["detail"]
        if(reason!=null)return@runCatching reason to message
        when (detail) {
            is JsonObject -> {
                detail["code"]?.jsonPrimitive?.contentOrNull to
                    detail["message"]?.jsonPrimitive?.contentOrNull
            }
            is JsonArray -> {
                val locations=detail.mapNotNull{(it as? JsonObject)?.get("loc") as? JsonArray}
                    .map{path->path.mapNotNull{(it as? JsonPrimitive)?.contentOrNull}}
                val accuracyOnly=locations.isNotEmpty() && locations.size==detail.size &&
                    locations.all{it.takeLast(2)==listOf("current_position","accuracy_m")}
                (if(accuracyOnly)"position_accuracy_insufficient" else "request_validation_failed") to
                    (message ?: if(accuracyOnly)"현재 위치 정확도를 다시 확인하세요." else "요청 값이 서버 계약과 다릅니다.")
            }
            else -> status to (message ?: (detail as? JsonPrimitive)?.contentOrNull)
        }
    }.getOrDefault(null to null)

    private fun encode(value: String): String = URLEncoder
        .encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
