package kr.co.navi.mobility.demo

import kr.co.navi.mobility.data.model.*
import kr.co.navi.mobility.guidance.contract.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlin.math.ceil

/** User-declared offline course; no server session, inferred obstacle position or Graph writes. */
@Serializable
data class HackathonCourse(
    @SerialName("scope_revision") val scopeRevision: String,
    @SerialName("dataset_revision") val datasetRevision: String,
    @SerialName("graph_sha256") val graphSha256: String,
    @SerialName("region_id") val regionId: String,
    @SerialName("origin_node") val originNode: String,
    @SerialName("destination_node") val destinationNode: String,
    @SerialName("preset_id") val presetId: String,
    @SerialName("baseline_segments") val baseline: List<RouteSegmentDto>,
    @SerialName("detour_segment") val east: RouteSegmentDto,
) {
    init {
        require(baseline.size==2 && baseline.all{it.geometry.size>=2} && east.geometry.size>=2)
        require(baseline[0].geometry.last()==baseline[1].geometry.first())
        require(baseline[0].geometry.first()==east.geometry.first())
        require(baseline[1].geometry.last()==east.geometry.last())
        require(baseline.none{it.edgeId==east.edgeId})
    }
    val approach get()=baseline.first().geometry
    val triggerPoint get()=approach.last().geo()

    fun canSwitch(position: GeoCoordinate): Boolean =
        geodesicDistanceMeters(position,triggerPoint)<=12.0 &&
            nearestForwardRouteSegment(approach,position,3.0)!=null

    fun initial(session: String)=route(session,1,baseline)

    fun detour(session: String,position: GeoCoordinate): RouteResultDto {
        require(canSwitch(position)){"지정 장애물 앞의 보행로에서 전환하세요."}
        val match=requireNotNull(nearestForwardRouteSegment(approach,position,3.0))
        val a=approach[match.segmentIndex];val b=approach[match.segmentIndex+1]
        val projected=listOf(a[0]+(b[0]-a[0])*match.fraction,a[1]+(b[1]-a[1])*match.fraction)
        // Start at the live position, retreat along only the already-traversed approach, then go east.
        val retreat=(listOf(listOf(position.longitude,position.latitude),projected)+approach.take(match.segmentIndex+1).reversed())
            .fold(mutableListOf<List<Double>>()){points,p->
                if(points.isEmpty() || geodesicDistanceMeters(points.last().geo(),p.geo())>0.01)points.add(p)
                points
            }
        require(retreat.size>=2)
        return route(session,2,listOf(baseline.first().copy(geometry=retreat),east))
    }

    private fun route(session: String,revision: Int,segments: List<RouteSegmentDto>): RouteResultDto {
        val geometry=segments.flatMapIndexed{i,s->if(i==0)s.geometry else s.geometry.drop(1)}
        val distance=geometry.zipWithNext().sumOf{(a,b)->geodesicDistanceMeters(a.geo(),b.geo())}
        return RouteResultDto(alignmentSource="poc_start",distanceM=distance,estimatedMinutes=ceil(distance/60).toInt(),
            routeType="demo_preset",profile="demo_jeonju",originNode=if(revision==1)originNode else "PRESET_CURRENT",
            destinationNode=destinationNode,edgeIds=segments.map{it.edgeId},geometry=geometry,segments=segments,
            reasons=listOf("user_declared_fixed_course"),warnings=listOf("사용자 지정 시연 경로 · 현장 접근성 미검증"),
            provenance=ProvenanceSummaryDto(sources=listOf("user_declared_route","osm_user_handoff"),unverifiedEdges=segments.size),
            sessionId=session,graphRevision=0,routeRevision=revision,scopeRevision=scopeRevision,
            datasetRevision=datasetRevision,graphSha256=graphSha256,regionId=regionId,
            calculatedOrigin=CoordinateDto(geometry.first()[1],geometry.first()[0]))
    }
    companion object {
        private val json=Json{ignoreUnknownKeys=true}
        fun parse(text: String)=json.decodeFromString<HackathonCourse>(text)
        private fun List<Double>.geo()=GeoCoordinate(this[1],this[0])
    }
}
