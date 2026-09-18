package kr.co.navi.mobility.data.remote

import kr.co.navi.mobility.data.model.CoordinateDto
import kr.co.navi.mobility.data.model.GraphEnrichmentSimulationRequestDto
import kr.co.navi.mobility.data.model.ObservationCandidateCreateDto
import kr.co.navi.mobility.data.model.SessionRerouteRequestDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviApiClientTest {
    @Test fun `field access code accompanies bootstrap and reroute but never URL`() = runTest {
        val requests = mutableListOf<HttpRequest>()
        val token = "field-test-code-" + "a".repeat(32)
        val client = NaviApiClient("https://field.example.com", HttpTransport {
            requests += it
            HttpResponse(200, "{}")
        }, accessToken = token)
        client.getJeonjuBootstrap()
        client.rerouteJeonju("session-test", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals("Bearer $token", it.headers["Authorization"])
            assertFalse(it.url.contains(token))
        }
    }

    @Test
    fun `graph enrichment endpoints expose pending candidates and read only simulation`() = runTest {
        val captured = mutableListOf<HttpRequest>()
        val transport = HttpTransport { request ->
            captured += request
            when {
                request.url.endsWith("/graph-enrichment/summary") -> HttpResponse(
                    200,
                    """{
                        "available":true,
                        "candidate_count":247,
                        "candidate_edge_count":196,
                        "route_affecting_candidate_count":17,
                        "evidence_only_candidate_count":230,
                        "diagnostic_candidate_count":12,
                        "approval_eligible_candidate_count":235,
                        "orthophoto_referenced_candidate_count":247,
                        "all_pending":true,
                        "all_unverified":true,
                        "graph_update_allowed":false
                    }""".trimIndent(),
                )
                request.url.contains("/graph-enrichment/candidates?") -> HttpResponse(
                    200,
                    """{
                        "available":true,
                        "total":1,
                        "offset":0,
                        "limit":250,
                        "candidates":[{
                            "candidate_id":"GEC-01",
                            "edge_id":"E15",
                            "type":"stairs_attribute_candidate",
                            "source":"spatial_evaluation_candidate",
                            "status":"pending",
                            "verified":false,
                            "graph_update_allowed":false,
                            "requires_human_review":true,
                            "priority":"high",
                            "routing_impact":"wheelchair_edge_exclusion_after_approval",
                            "mapping_status":"unique",
                            "mapping_quality":"single_source_unique_match",
                            "candidate_class":"routing_attribute",
                            "simulation_allowed":true,
                            "approval_eligible":true,
                            "quality_flags":[],
                            "proposed_changes":{"stairs":true},
                            "current_values":{"stairs":false},
                            "evidence_count":1,
                            "source_types":["ngii_topographic_map"],
                            "evidence":[{
                                "source_type":"ngii_topographic_map",
                                "source_dataset_id":"ngii_digital_topographic_map_anyang_corridor_20260917",
                                "source_feature_id":"1000037612047C03910000000000000111",
                                "source_feature_code":"C0390000",
                                "mapping_status":"unique",
                                "mapping_distance_m":2.771062,
                                "mapping_score":0.395665,
                                "source_sheet_id":"37612047",
                                "source_year":2025
                            }],
                            "visual_evidence_refs":[{
                                "reference_id":"ORTHO-GEC-01-37612047",
                                "source_dataset_id":"ngii_orthophoto_2025_anyang_corridor_20260917",
                                "sheet_id":"37612047",
                                "pixel_row":100,
                                "pixel_col":200,
                                "pixel_size_m":[0.25,0.25],
                                "reference_status":"visual_qa_only_provisional_georeferencing",
                                "allowed_use":"human_visual_spatial_qa",
                                "control_point_count":0,
                                "control_point_rmse_m":null,
                                "geometry_correction_allowed":false,
                                "graph_update_allowed":false,
                                "verified":false
                            }],
                            "lat":37.4,
                            "lon":126.9,
                            "created_at":"2026-09-18T09:26:23+09:00"
                        }]
                    }""".trimIndent(),
                )
                request.url.endsWith("/graph-enrichment/simulate") -> HttpResponse(
                    200,
                    """{
                        "status":"ok",
                        "candidate_ids":["GEC-01"],
                        "applied_edge_ids":["E15"],
                        "baseline_candidate_edge_ids":["E15"],
                        "baseline":{
                            "distance_m":98.1,
                            "estimated_minutes":2,
                            "route_type":"accessible",
                            "profile":"wheelchair",
                            "origin_node":"A",
                            "destination_node":"B",
                            "edge_ids":["E15"],
                            "geometry":[[126.9,37.4],[126.901,37.401]],
                            "provenance":{}
                        },
                        "simulated":{
                            "distance_m":212.2,
                            "estimated_minutes":4,
                            "route_type":"accessible",
                            "profile":"wheelchair",
                            "origin_node":"A",
                            "destination_node":"B",
                            "edge_ids":["E20"],
                            "geometry":[[126.9,37.4],[126.901,37.401]],
                            "provenance":{}
                        },
                        "route_changed":true,
                        "difference_m":114.1,
                        "graph_revision":7,
                        "graph_mutated":false,
                        "database_mutated":false
                    }""".trimIndent(),
                )
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = NaviApiClient("http://127.0.0.1:8000", transport)

        val summary = client.getGraphEnrichmentSummary()
        val candidates = client.getGraphCandidates(routeAffecting = true)
        val simulation = client.simulateGraphCandidate(
            GraphEnrichmentSimulationRequestDto(
                origin = CoordinateDto(37.4, 126.9),
                destination = CoordinateDto(37.401, 126.901),
                candidateIds = listOf("GEC-01"),
            ),
        )

        assertEquals(17, summary.routeAffectingCandidateCount)
        assertEquals(12, summary.diagnosticCandidateCount)
        assertEquals("pending", candidates.candidates.single().status)
        assertFalse(candidates.candidates.single().verified)
        assertEquals("unique", candidates.candidates.single().mappingStatus)
        assertEquals(2025, candidates.candidates.single().evidence.single()["source_year"]?.toString()?.toInt())
        assertFalse(candidates.candidates.single().currentValues["stairs"].toString().toBoolean())
        assertEquals("37612047", candidates.candidates.single().visualEvidenceRefs.single().sheetId)
        assertEquals(114.1, simulation.differenceM ?: 0.0, 0.001)
        assertFalse(simulation.graphMutated)
        assertFalse(simulation.databaseMutated)
        assertTrue(captured[1].url.endsWith("route_affecting=true&limit=250"))
        assertTrue(captured[2].body.orEmpty().contains("\"candidate_ids\":[\"GEC-01\"]"))
    }

    @Test
    fun `manual observation remains pending and unverified`() = runTest {
        var captured: HttpRequest? = null
        val transport = HttpTransport { request ->
            captured = request
            HttpResponse(
                201,
                """{
                    "candidate_id":"MOB_1",
                    "edge_id":"E15",
                    "type":"construction",
                    "source":"manual_camera",
                    "confidence":null,
                    "status":"pending",
                    "verified":false,
                    "session_id":"S01"
                }""".trimIndent(),
            )
        }
        val client = NaviApiClient("http://127.0.0.1:8000", transport)

        val result = client.createCandidate(
            ObservationCandidateCreateDto(
                edgeId = "E15",
                type = "construction",
                source = "manual_camera",
                sessionId = "S01",
            ),
        )

        assertEquals("pending", result.status)
        assertFalse(result.verified)
        assertNull(result.confidence)
        assertEquals(HttpMethod.POST, captured?.method)
        assertEquals("http://127.0.0.1:8000/observations/candidates", captured?.url)
        assertTrue(captured?.body.orEmpty().contains("\"edge_id\":\"E15\""))
    }

    @Test
    fun `session id is URL encoded for reroute`() = runTest {
        var capturedUrl = ""
        val transport = HttpTransport { request ->
            capturedUrl = request.url
            HttpResponse(
                200,
                """{
                    "status":"rerouted",
                    "session_id":"session / 1",
                    "temporary_blocked_edge_ids":["E15"],
                    "graph_revision":1,
                    "route_affected":true,
                    "route_changed":true
                }""".trimIndent(),
            )
        }
        val client = NaviApiClient("http://127.0.0.1:8000/", transport)

        client.reroute(
            "session / 1",
            SessionRerouteRequestDto(listOf("E15"), "construction"),
        )

        assertTrue(capturedUrl.endsWith("/route/sessions/session%20%2F%201/reroute"))
    }

    @Test
    fun `structured backend error is exposed`() = runTest {
        val client = NaviApiClient(
            "http://127.0.0.1:8000",
            HttpTransport {
                HttpResponse(
                    404,
                    """{"detail":{"code":"session_not_found","message":"세션이 만료되었습니다."}}""",
                )
            },
        )

        val error = runCatching {
            client.reroute("missing", SessionRerouteRequestDto(listOf("E15"), "blocked_path"))
        }.exceptionOrNull() as NaviApiException

        assertEquals(404, error.statusCode)
        assertEquals("session_not_found", error.errorCode)
        assertEquals("세션이 만료되었습니다.", error.message)
    }

    @Test
    fun `PoC reason code survives rejected status and can be deferred`() = runTest {
        val client=NaviApiClient("http://127.0.0.1:8000",HttpTransport {
            HttpResponse(422,"""{"status":"rejected","reason_code":"position_ambiguous","message":"위치가 모호합니다."}""")
        })
        val error=runCatching {client.rerouteJeonju("session-id",kotlinx.serialization.json.JsonObject(emptyMap()))}.exceptionOrNull() as NaviApiException
        assertEquals("position_ambiguous",error.errorCode)
        assertEquals("위치가 모호합니다.",error.message)
    }

    @Test
    fun `accuracy validation errors are recoverable without hiding unrelated schema failures`() = runTest {
        suspend fun error(body: String)=runCatching {
            NaviApiClient("http://127.0.0.1:8000",HttpTransport {HttpResponse(422,body)})
                .rerouteJeonju("session-id",kotlinx.serialization.json.JsonObject(emptyMap()))
        }.exceptionOrNull() as NaviApiException
        assertEquals("position_accuracy_insufficient",error("""{"detail":[{"type":"less_than_equal","loc":["body","current_position","accuracy_m"],"input":4,"msg":"must be <=3"}]}""").errorCode)
        assertEquals("request_validation_failed",error("""{"detail":[{"loc":["body","current_position","accuracy_m"]},{"loc":["body","reason"]}]}""").errorCode)
        assertEquals("request_validation_failed",error("""{"detail":[{"loc":["body","avoidance_upserts",0,"frame_id"]}]}""").errorCode)
    }
}
