package kr.co.navi.mobility.ui.components

import android.graphics.Color as AndroidColor
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.co.navi.mobility.data.model.RouteResultDto
import kr.co.navi.mobility.data.model.GraphEnrichmentCandidateDto
import kr.co.navi.mobility.ui.theme.NaviCaution
import kr.co.navi.mobility.ui.theme.NaviBlock
import kr.co.navi.mobility.ui.theme.NaviBlue
import kr.co.navi.mobility.ui.theme.NaviCanvas
import kr.co.navi.mobility.ui.theme.NaviInkMuted
import kr.co.navi.mobility.ui.theme.NaviViolet
import kr.co.navi.mobility.ui.theme.NaviPass
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.sources.GeoJsonSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun RouteMap(
    standard: RouteResultDto,
    accessible: RouteResultDto,
    blockGeometry: List<List<Double>>,
    rerouted: Boolean,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    val updates = remember(mapView) { MapUpdateGuard() }
    var mapReady by remember { mutableStateOf(false) }
    var mapFailed by remember { mutableStateOf(false) }
    var startScreenPoint by remember { mutableStateOf<PointF?>(null) }
    var endScreenPoint by remember { mutableStateOf<PointF?>(null) }
    val markerRadiusPx = with(LocalDensity.current) { 18.dp.roundToPx() }
    val standardJson = remember(standard.geometry) { lineGeoJson(standard.geometry) }
    val accessibleJson = remember(accessible.geometry) { lineGeoJson(accessible.geometry) }
    val blockJson = remember(blockGeometry) { lineGeoJson(blockGeometry) }

    LaunchedEffect(mapView) {
        kotlinx.coroutines.delay(10_000)
        if (!mapReady) mapFailed = true
    }

    DisposableEffect(mapView, standardJson, accessibleJson, blockJson, rerouted, accessible.sessionId, accessible.routeRevision) {
        val request = updates.begin()
        runCatching {
            mapView.getMapAsync { map ->
                if (!request.isCurrent()) return@getMapAsync
                val existingStyle = map.style
                if (existingStyle == null || !existingStyle.isFullyLoaded) {
                    map.setStyle(Style.Builder().fromJson(BASE_STYLE_JSON)) { style ->
                        if (!request.isCurrent()) return@setStyle
                        installOrUpdateRoutes(
                            style,
                            standardJson,
                            accessibleJson,
                            blockJson,
                            rerouted,
                        )
                        fitRoute(mapView, standard.geometry + accessible.geometry, request::isCurrent)
                        positionEndpointBadges(mapView, map, accessible.geometry, request::isCurrent) { start, end ->
                            startScreenPoint = start
                            endScreenPoint = end
                        }
                        mapReady = true
                    }
                } else {
                    installOrUpdateRoutes(
                        existingStyle,
                        standardJson,
                        accessibleJson,
                        blockJson,
                        rerouted,
                    )
                    fitRoute(mapView, standard.geometry + accessible.geometry, request::isCurrent)
                    positionEndpointBadges(mapView, map, accessible.geometry, request::isCurrent) { start, end ->
                        startScreenPoint = start
                        endScreenPoint = end
                    }
                    mapReady = true
                }
            }
        }.onFailure { mapFailed = true }
        onDispose { request.dispose() }
    }

    Box(modifier.background(NaviCanvas)) {
        RouteCanvas(
            standard = standard.geometry,
            accessible = accessible.geometry,
            blockGeometry = blockGeometry,
            rerouted = rerouted,
            modifier = Modifier.fillMaxSize(),
        )
        if (!mapFailed) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!mapReady || mapFailed) {
            Text(
                text = if (mapFailed) "오프라인 경로 보기" else "지도를 준비하고 있습니다",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = NaviInkMuted,
            )
        }
        MapLegend(
            rerouted = rerouted,
            demo = accessible.profile == "demo_jeonju",
            showBlock = blockGeometry.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )
        startScreenPoint?.let { point ->
            EndpointBadge(
                label = "A",
                description = "출발 A",
                color = NaviBlue,
                modifier = Modifier.offset {
                    IntOffset(point.x.toInt() - markerRadiusPx, point.y.toInt() - markerRadiusPx)
                },
            )
        }
        endScreenPoint?.let { point ->
            EndpointBadge(
                label = "B",
                description = "도착 B",
                color = NaviViolet,
                modifier = Modifier.offset {
                    IntOffset(point.x.toInt() - markerRadiusPx, point.y.toInt() - markerRadiusPx)
                },
            )
        }
    }
}

@Composable
fun GraphCandidateImpactMap(
    candidates: List<GraphEnrichmentCandidateDto>,
    selectedCandidateId: String?,
    baseline: RouteResultDto?,
    simulated: RouteResultDto?,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapViewWithLifecycle()
    var mapReady by remember { mutableStateOf(false) }
    var mapFailed by remember { mutableStateOf(false) }
    val selected = candidates.firstOrNull { it.candidateId == selectedCandidateId }
    val stairsJson = remember(candidates) {
        candidatePointGeoJson(candidates.filter { it.type == "stairs_attribute_candidate" })
    }
    val slopeJson = remember(candidates) {
        candidatePointGeoJson(candidates.filter { it.type == "dem_slope_diagnostic_candidate" })
    }
    val pedestrianJson = remember(candidates) {
        candidatePointGeoJson(candidates.filter { it.type == "pedestrian_area_evidence" })
    }
    val crossingJson = remember(candidates) {
        candidatePointGeoJson(
            candidates.filter {
                it.type == "crosswalk_geometry_evidence" ||
                    it.type == "grade_separated_crossing_evidence"
            },
        )
    }
    val curbJson = remember(candidates) {
        candidatePointGeoJson(candidates.filter { it.type == "curb_presence_evidence" })
    }
    val selectedJson = remember(selected) { candidatePointGeoJson(listOfNotNull(selected)) }
    val baselineJson = remember(baseline?.geometry) { lineGeoJson(baseline?.geometry.orEmpty()) }
    val simulatedJson = remember(simulated?.geometry) { lineGeoJson(simulated?.geometry.orEmpty()) }

    LaunchedEffect(
        mapView,
        stairsJson,
        slopeJson,
        pedestrianJson,
        crossingJson,
        curbJson,
        selectedJson,
        baselineJson,
        simulatedJson,
    ) {
        runCatching {
            mapView.getMapAsync { map ->
                val update: (Style) -> Unit = { style ->
                    installOrUpdateCandidateImpact(
                        style = style,
                        stairsJson = stairsJson,
                        slopeJson = slopeJson,
                        pedestrianJson = pedestrianJson,
                        crossingJson = crossingJson,
                        curbJson = curbJson,
                        selectedJson = selectedJson,
                        baselineJson = baselineJson,
                        simulatedJson = simulatedJson,
                    )
                    fitCandidateImpact(
                        mapView = mapView,
                        candidates = candidates,
                        selected = selected,
                        baselineGeometry = baseline?.geometry.orEmpty(),
                        simulatedGeometry = simulated?.geometry.orEmpty(),
                    )
                    mapReady = true
                }
                val existingStyle = map.style
                if (existingStyle == null || !existingStyle.isFullyLoaded) {
                    map.setStyle(Style.Builder().fromJson(BASE_STYLE_JSON), update)
                } else {
                    update(existingStyle)
                }
            }
        }.onFailure { mapFailed = true }
    }

    Box(
        modifier = modifier
            .background(NaviCanvas)
            .semantics { contentDescription = "공간데이터 후보 영향 지도" },
    ) {
        CandidateImpactCanvas(
            candidates = candidates,
            selectedCandidateId = selectedCandidateId,
            baseline = baseline?.geometry.orEmpty(),
            simulated = simulated?.geometry.orEmpty(),
            modifier = Modifier.fillMaxSize(),
        )
        if (!mapFailed) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        }
        if (!mapReady || mapFailed) {
            Text(
                text = if (mapFailed) "오프라인 후보 지도" else "후보 지도를 준비하고 있습니다",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = NaviInkMuted,
            )
        }
        CandidateImpactLegend(
            candidates = candidates,
            hasSimulation = baseline != null,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        )
    }
}

@Composable
private fun CandidateImpactLegend(
    candidates: List<GraphEnrichmentCandidateDto>,
    hasSimulation: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.small,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (candidates.any { it.type == "stairs_attribute_candidate" }) {
                LegendItem("계단 후보", NaviBlock)
            }
            if (candidates.any { it.type == "dem_slope_diagnostic_candidate" }) {
                LegendItem("DEM 경사 진단", NaviViolet)
            }
            if (candidates.any { it.type == "pedestrian_area_evidence" }) {
                LegendItem("보행공간 근거", NaviBlue)
            }
            if (candidates.any {
                    it.type == "crosswalk_geometry_evidence" ||
                        it.type == "grade_separated_crossing_evidence"
                }
            ) {
                LegendItem("횡단시설 근거", NaviPass)
            }
            if (candidates.any { it.type == "curb_presence_evidence" }) {
                LegendItem("연석 근거", NaviCaution)
            }
            if (hasSimulation) {
                LegendItem("현재 Graph", Color(0xFF64748B))
                LegendItem("후보 임시 적용", NaviViolet)
            }
        }
    }
}

@Composable
private fun EndpointBadge(
    label: String,
    description: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .background(Color.White, CircleShape)
            .padding(3.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun MapLegend(
    rerouted: Boolean,
    demo: Boolean = false,
    showBlock: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.small,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (!demo) LegendItem("일반 최단", Color(0xFF64748B))
            LegendItem(if (demo) "시연 경로 · 접근성 미확인" else if (rerouted) "재탐색 경로" else "접근 가능", if (rerouted) NaviViolet else NaviBlue)
            if (rerouted && showBlock) LegendItem("임시 회피", Color(0xFFC8202F))
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(width = 22.dp, height = 5.dp).background(color, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = NaviInkMuted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapView(context).apply { onCreate(null) } }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

private fun installOrUpdateRoutes(
    style: Style,
    standardJson: String,
    accessibleJson: String,
    blockJson: String,
    rerouted: Boolean,
) {
    addOrUpdateSource(style, STANDARD_SOURCE, standardJson)
    addOrUpdateSource(style, ACCESSIBLE_SOURCE, accessibleJson)
    addOrUpdateSource(style, BLOCK_SOURCE, blockJson)

    if (style.getLayer(STANDARD_LAYER) == null) {
        style.addLayer(
            LineLayer(STANDARD_LAYER, STANDARD_SOURCE).withProperties(
                lineColor(AndroidColor.parseColor("#64748B")),
                lineWidth(4f),
                lineOpacity(0.72f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
    }
    val accessibleColor = if (rerouted) "#7C3AED" else "#2563EB"
    val accessibleLayer = style.getLayerAs<LineLayer>(ACCESSIBLE_LAYER)
    if (accessibleLayer == null) {
        style.addLayer(
            LineLayer(ACCESSIBLE_LAYER, ACCESSIBLE_SOURCE).withProperties(
                lineColor(AndroidColor.parseColor(accessibleColor)),
                lineWidth(7f),
                lineOpacity(0.94f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
    } else {
        accessibleLayer.setProperties(lineColor(AndroidColor.parseColor(accessibleColor)))
    }
    if (style.getLayer(BLOCK_LAYER) == null) {
        style.addLayer(
            LineLayer(BLOCK_CASING_LAYER, BLOCK_SOURCE).withProperties(
                lineColor(AndroidColor.WHITE),
                lineWidth(12f),
                lineOpacity(0.9f),
                lineCap(LINE_CAP_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(BLOCK_LAYER, BLOCK_SOURCE).withProperties(
                lineColor(AndroidColor.parseColor("#C8202F")),
                lineWidth(8f),
                lineOpacity(0.96f),
                lineDasharray(arrayOf(1.2f, 1.2f)),
                lineCap(LINE_CAP_ROUND),
            ),
        )
    }
}

private fun installOrUpdateCandidateImpact(
    style: Style,
    stairsJson: String,
    slopeJson: String,
    pedestrianJson: String,
    crossingJson: String,
    curbJson: String,
    selectedJson: String,
    baselineJson: String,
    simulatedJson: String,
) {
    addOrUpdateSource(style, CANDIDATE_STAIRS_SOURCE, stairsJson)
    addOrUpdateSource(style, CANDIDATE_SLOPE_SOURCE, slopeJson)
    addOrUpdateSource(style, CANDIDATE_PEDESTRIAN_SOURCE, pedestrianJson)
    addOrUpdateSource(style, CANDIDATE_CROSSING_SOURCE, crossingJson)
    addOrUpdateSource(style, CANDIDATE_CURB_SOURCE, curbJson)
    addOrUpdateSource(style, SELECTED_CANDIDATE_SOURCE, selectedJson)
    addOrUpdateSource(style, CANDIDATE_BASELINE_SOURCE, baselineJson)
    addOrUpdateSource(style, CANDIDATE_SIMULATED_SOURCE, simulatedJson)

    if (style.getLayer(CANDIDATE_BASELINE_LAYER) == null) {
        style.addLayer(
            LineLayer(CANDIDATE_BASELINE_LAYER, CANDIDATE_BASELINE_SOURCE).withProperties(
                lineColor(AndroidColor.parseColor("#64748B")),
                lineWidth(5f),
                lineOpacity(0.82f),
                lineDasharray(arrayOf(1.4f, 1.1f)),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
    }
    if (style.getLayer(CANDIDATE_SIMULATED_LAYER) == null) {
        style.addLayer(
            LineLayer(CANDIDATE_SIMULATED_LAYER, CANDIDATE_SIMULATED_SOURCE).withProperties(
                lineColor(AndroidColor.parseColor("#7C3AED")),
                lineWidth(7f),
                lineOpacity(0.96f),
                lineCap(LINE_CAP_ROUND),
                lineJoin(LINE_JOIN_ROUND),
            ),
        )
    }
    addCandidateCircleLayer(style, CANDIDATE_STAIRS_LAYER, CANDIDATE_STAIRS_SOURCE, "#C8202F")
    addCandidateCircleLayer(style, CANDIDATE_SLOPE_LAYER, CANDIDATE_SLOPE_SOURCE, "#7C3AED")
    addCandidateCircleLayer(style, CANDIDATE_PEDESTRIAN_LAYER, CANDIDATE_PEDESTRIAN_SOURCE, "#2563EB")
    addCandidateCircleLayer(style, CANDIDATE_CROSSING_LAYER, CANDIDATE_CROSSING_SOURCE, "#007A61")
    addCandidateCircleLayer(style, CANDIDATE_CURB_LAYER, CANDIDATE_CURB_SOURCE, "#A85A00")
    if (style.getLayer(SELECTED_CANDIDATE_LAYER) == null) {
        style.addLayer(
            CircleLayer(SELECTED_CANDIDATE_LAYER, SELECTED_CANDIDATE_SOURCE).withProperties(
                circleColor(AndroidColor.parseColor("#7C3AED")),
                circleRadius(10f),
                circleOpacity(1f),
                circleStrokeColor(AndroidColor.WHITE),
                circleStrokeWidth(3f),
            ),
        )
    }
}

private fun addCandidateCircleLayer(
    style: Style,
    layerId: String,
    sourceId: String,
    color: String,
) {
    if (style.getLayer(layerId) != null) return
    style.addLayer(
        CircleLayer(layerId, sourceId).withProperties(
            circleColor(AndroidColor.parseColor(color)),
            circleRadius(6f),
            circleOpacity(0.9f),
            circleStrokeColor(AndroidColor.WHITE),
            circleStrokeWidth(2f),
        ),
    )
}

private fun addOrUpdateSource(style: Style, id: String, geoJson: String) {
    val source = style.getSourceAs<GeoJsonSource>(id)
    if (source == null) style.addSource(GeoJsonSource(id, geoJson)) else source.setGeoJson(geoJson)
}

private fun fitRoute(mapView: MapView, geometry: List<List<Double>>, isCurrent: () -> Boolean) {
    val points = geometry.mapNotNull { point ->
        if (point.size >= 2) LatLng(point[1], point[0]) else null
    }
    if (points.size < 2) return
    mapView.getMapAsync { map ->
        if (!isCurrent()) return@getMapAsync
        val bounds = LatLngBounds.Builder().includes(points).build()
        val padding = (72 * mapView.resources.displayMetrics.density).toInt()
        runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding)) }
            .onFailure {
                map.cameraPosition = CameraPosition.Builder().target(points.first()).zoom(15.0).build()
            }
    }
}

private fun fitCandidateImpact(
    mapView: MapView,
    candidates: List<GraphEnrichmentCandidateDto>,
    selected: GraphEnrichmentCandidateDto?,
    baselineGeometry: List<List<Double>>,
    simulatedGeometry: List<List<Double>>,
) {
    val routePoints = (baselineGeometry + simulatedGeometry).mapNotNull { point ->
        if (point.size >= 2) LatLng(point[1], point[0]) else null
    }
    val candidatePoints = candidates.map { LatLng(it.lat, it.lon) }
    val points = if (routePoints.size >= 2) {
        routePoints + listOfNotNull(selected?.let { LatLng(it.lat, it.lon) })
    } else {
        candidatePoints
    }
    if (points.isEmpty()) return
    mapView.getMapAsync { map ->
        if (points.size == 1) {
            map.cameraPosition = CameraPosition.Builder().target(points.first()).zoom(17.0).build()
            return@getMapAsync
        }
        val bounds = LatLngBounds.Builder().includes(points).build()
        val padding = (54 * mapView.resources.displayMetrics.density).toInt()
        runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding)) }
            .onFailure {
                map.cameraPosition = CameraPosition.Builder().target(points.first()).zoom(15.0).build()
            }
    }
}

private fun positionEndpointBadges(
    mapView: MapView,
    map: MapLibreMap,
    geometry: List<List<Double>>,
    isCurrent: () -> Boolean,
    onPositioned: (PointF, PointF) -> Unit,
) {
    val start = geometry.firstOrNull()?.takeIf { it.size >= 2 } ?: return
    val end = geometry.lastOrNull()?.takeIf { it.size >= 2 } ?: return
    map.uiSettings.setAllGesturesEnabled(false)
    mapView.post {
        if (!isCurrent()) return@post
        onPositioned(
            map.projection.toScreenLocation(LatLng(start[1], start[0])),
            map.projection.toScreenLocation(LatLng(end[1], end[0])),
        )
    }
}

private fun lineGeoJson(geometry: List<List<Double>>): String = buildJsonObject {
    put("type", "FeatureCollection")
    put("features", buildJsonArray {
        if (geometry.size >= 2) {
            add(buildJsonObject {
                put("type", "Feature")
                put("properties", buildJsonObject {})
                put("geometry", buildJsonObject {
                    put("type", "LineString")
                    put("coordinates", JsonArray(geometry.map { point ->
                        JsonArray(point.take(2).map(::JsonPrimitive))
                    }))
                })
            })
        }
    })
}.toString()

private fun candidatePointGeoJson(candidates: List<GraphEnrichmentCandidateDto>): String =
    buildJsonObject {
        put("type", "FeatureCollection")
        put("features", buildJsonArray {
            candidates.forEach { candidate ->
                add(buildJsonObject {
                    put("type", "Feature")
                    put("properties", buildJsonObject {
                        put("candidate_id", candidate.candidateId)
                        put("edge_id", candidate.edgeId)
                        put("type", candidate.type)
                    })
                    put("geometry", buildJsonObject {
                        put("type", "Point")
                        put("coordinates", buildJsonArray {
                            add(JsonPrimitive(candidate.lon))
                            add(JsonPrimitive(candidate.lat))
                        })
                    })
                })
            }
        })
    }.toString()

@Composable
private fun RouteCanvas(
    standard: List<List<Double>>,
    accessible: List<List<Double>>,
    blockGeometry: List<List<Double>>,
    rerouted: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.background(Color(0xFFF1F5F9))) {
        val all = standard + accessible + blockGeometry
        if (all.isEmpty()) return@Canvas
        val minLon = all.minOf { it[0] }
        val maxLon = all.maxOf { it[0] }
        val minLat = all.minOf { it[1] }
        val maxLat = all.maxOf { it[1] }
        val lonSpan = (maxLon - minLon).takeIf { it > 0 } ?: 1.0
        val latSpan = (maxLat - minLat).takeIf { it > 0 } ?: 1.0

        fun path(points: List<List<Double>>): Path = Path().apply {
            points.forEachIndexed { index, point ->
                val x = ((point[0] - minLon) / lonSpan).toFloat() * size.width * 0.86f + size.width * 0.07f
                val y = size.height - (((point[1] - minLat) / latSpan).toFloat() * size.height * 0.82f + size.height * 0.09f)
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path(standard), Color(0xFF64748B), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        drawPath(
            path(accessible),
            if (rerouted) NaviViolet else NaviBlue,
            style = Stroke(7.dp.toPx(), cap = StrokeCap.Round),
        )
        drawPath(path(blockGeometry), Color.White, style = Stroke(12.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path(blockGeometry), Color(0xFFC8202F), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun CandidateImpactCanvas(
    candidates: List<GraphEnrichmentCandidateDto>,
    selectedCandidateId: String?,
    baseline: List<List<Double>>,
    simulated: List<List<Double>>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.background(Color(0xFFF1F5F9))) {
        val candidateCoordinates = candidates.map { listOf(it.lon, it.lat) }
        val all = baseline + simulated + candidateCoordinates
        if (all.isEmpty()) return@Canvas
        val minLon = all.minOf { it[0] }
        val maxLon = all.maxOf { it[0] }
        val minLat = all.minOf { it[1] }
        val maxLat = all.maxOf { it[1] }
        val lonSpan = (maxLon - minLon).takeIf { it > 0 } ?: 1.0
        val latSpan = (maxLat - minLat).takeIf { it > 0 } ?: 1.0

        fun point(coordinate: List<Double>): Offset = Offset(
            x = ((coordinate[0] - minLon) / lonSpan).toFloat() * size.width * 0.86f + size.width * 0.07f,
            y = size.height - (((coordinate[1] - minLat) / latSpan).toFloat() * size.height * 0.82f + size.height * 0.09f),
        )
        fun path(points: List<List<Double>>): Path = Path().apply {
            points.forEachIndexed { index, coordinate ->
                val projected = point(coordinate)
                if (index == 0) moveTo(projected.x, projected.y) else lineTo(projected.x, projected.y)
            }
        }

        if (baseline.size >= 2) {
            drawPath(path(baseline), Color(0xFF64748B), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        }
        if (simulated.size >= 2) {
            drawPath(path(simulated), NaviViolet, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
        }
        candidates.forEach { candidate ->
            val center = point(listOf(candidate.lon, candidate.lat))
            val selected = candidate.candidateId == selectedCandidateId
            drawCircle(Color.White, radius = (if (selected) 10.dp else 7.dp).toPx(), center = center)
            drawCircle(
                if (selected) NaviViolet else candidateMapColor(candidate.type),
                radius = (if (selected) 7.dp else 5.dp).toPx(),
                center = center,
            )
        }
    }
}

private const val STANDARD_SOURCE = "navi-standard-source"
private const val ACCESSIBLE_SOURCE = "navi-accessible-source"
private const val BLOCK_SOURCE = "navi-block-source"
private const val STANDARD_LAYER = "navi-standard-layer"
private const val ACCESSIBLE_LAYER = "navi-accessible-layer"
private const val BLOCK_CASING_LAYER = "navi-block-casing-layer"
private const val BLOCK_LAYER = "navi-block-layer"
private fun candidateMapColor(type: String): Color = when (type) {
    "stairs_attribute_candidate" -> NaviBlock
    "dem_slope_diagnostic_candidate" -> NaviViolet
    "pedestrian_area_evidence" -> NaviBlue
    "crosswalk_geometry_evidence", "grade_separated_crossing_evidence" -> NaviPass
    "curb_presence_evidence" -> NaviCaution
    else -> NaviCaution
}

private const val CANDIDATE_STAIRS_SOURCE = "navi-candidate-stairs-source"
private const val CANDIDATE_SLOPE_SOURCE = "navi-candidate-slope-source"
private const val CANDIDATE_PEDESTRIAN_SOURCE = "navi-candidate-pedestrian-source"
private const val CANDIDATE_CROSSING_SOURCE = "navi-candidate-crossing-source"
private const val CANDIDATE_CURB_SOURCE = "navi-candidate-curb-source"
private const val SELECTED_CANDIDATE_SOURCE = "navi-selected-candidate-source"
private const val CANDIDATE_BASELINE_SOURCE = "navi-candidate-baseline-source"
private const val CANDIDATE_SIMULATED_SOURCE = "navi-candidate-simulated-source"
private const val CANDIDATE_STAIRS_LAYER = "navi-candidate-stairs-layer"
private const val CANDIDATE_SLOPE_LAYER = "navi-candidate-slope-layer"
private const val CANDIDATE_PEDESTRIAN_LAYER = "navi-candidate-pedestrian-layer"
private const val CANDIDATE_CROSSING_LAYER = "navi-candidate-crossing-layer"
private const val CANDIDATE_CURB_LAYER = "navi-candidate-curb-layer"
private const val SELECTED_CANDIDATE_LAYER = "navi-selected-candidate-layer"
private const val CANDIDATE_BASELINE_LAYER = "navi-candidate-baseline-layer"
private const val CANDIDATE_SIMULATED_LAYER = "navi-candidate-simulated-layer"

private val BASE_STYLE_JSON = """
{
  "version": 8,
  "name": "NaVi Light",
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {"id": "osm", "type": "raster", "source": "osm", "paint": {"raster-opacity": 0.82}}
  ]
}
""".trimIndent()
