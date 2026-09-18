package kr.co.navi.mobility.guidance.contract

import kotlin.math.abs
import kotlin.math.max

/**
 * Shared Live/Replay progress from measured poses and the immutable route snapshot.
 * Uses recorded monotonic time only; an arrival is never inferred from elapsed wall time.
 */
class PocNavigationProgressTracker {
    data class Progress(val remainingMeters: Double?,val arrived: Boolean,val reason: String)

    private data class Identity(
        val sessionId: String,val routeRevision: Int,val graphRevision: Int,
        val scopeRevision: String,val datasetRevision: String,
        val calibrationRevision: String,val trackingEpoch: Long,
    )
    private data class Segment(val index: Int,val from: GeoCoordinate,val to: GeoCoordinate,val length: Double,val match: RouteSegmentMatch)

    private var identity: Identity?=null
    private var lastStamp: FrameStamp?=null
    private var arrival=ArrivalGateProgress()
    private val arrivalConfig=ArrivalGateConfig(
        arrivalRadiusMeters=2.0,maximumAccuracyMeters=1.5f,
        requiredConsecutiveObservations=3,requiredDwellMillis=2_000,
    )

    fun reset() {identity=null;lastStamp=null;arrival=ArrivalGateProgress()}

    fun update(snapshot: RouteSnapshot,spatial: SpatialFrameContext): Progress {
        fun defer(reason: String): Progress {arrival=ArrivalGateProgress();return Progress(null,false,reason)}
        val geometry=snapshot.geometry
        if(snapshot.reasonCode!=null || geometry.size<2 || !snapshot.distanceMeters.isFinite() || snapshot.distanceMeters<=0.0) {
            reset();return Progress(null,false,"no_route")
        }
        val capture=spatial.capture
        val calibration=capture?.calibration
        val pose=spatial.localPose
        val user=spatial.geoCoordinate
        if(calibration==null || pose==null || user==null || spatial.trackingQuality!=TrackingQuality.TRACKING) return defer("alignment_unavailable")
        val currentIdentity=Identity(snapshot.sessionId,snapshot.revision,snapshot.graphRevision,snapshot.scopeRevision,snapshot.datasetRevision,calibration.revision,capture.trackingEpoch)
        if(identity!=currentIdentity) {
            identity=currentIdentity;lastStamp=null;arrival=ArrivalGateProgress()
        }
        val previousStamp=lastStamp
        val stamp=spatial.stamp
        // Keep the high-water stamp even after a bad input, so old frames cannot form a new run.
        if(previousStamp!=null && (stamp.timestampNanos<=previousStamp.timestampNanos || stamp.frameId<=previousStamp.frameId)) return defer("frame_out_of_order")
        lastStamp=stamp
        val statedAccuracy=spatial.navigationBudgetMeters()
        val calibrationError=calibration.errorAt(pose.position(),stamp.timestampNanos)
        if(statedAccuracy==null || !statedAccuracy.isFinite() || statedAccuracy<0 || !calibrationError.isFinite() || calibrationError<0) return defer("alignment_uncertain")
        val accuracy=max(statedAccuracy,calibrationError)
        if(accuracy>1.5) return defer("alignment_uncertain")
        if(previousStamp!=null && stamp.timestampNanos-previousStamp.timestampNanos>500_000_000L) return defer("frame_gap")

        val coordinates=geometry.map{listOf(it.longitude,it.latitude)}
        val bestMatch=nearestForwardRouteSegment(coordinates,user,3.0) ?: return defer("position_outside_route")
        val lengths=geometry.zipWithNext().map{(a,b)->geodesicDistanceMeters(a,b)}
        val totalLength=lengths.sum()
        if(!totalLength.isFinite() || totalLength<=0.01)return defer("no_route")
        val best=Segment(bestMatch.segmentIndex,geometry[bestMatch.segmentIndex],geometry[bestMatch.segmentIndex+1],lengths[bestMatch.segmentIndex],bestMatch)
        val uncertainty=accuracy+0.5 // OSM shape uncertainty remains separate from device accuracy.
        val competitors=geometry.zipWithNext().mapIndexedNotNull {index,(from,to)->
            if(index==best.index || lengths[index]<=0.01) return@mapIndexedNotNull null
            val match=nearestForwardRouteSegment(listOf(coordinates[index],coordinates[index+1]),user,3.0) ?: return@mapIndexedNotNull null
            Segment(index,from,to,lengths[index],match)
        }.filter{it.match.lateralErrorMeters<=best.match.lateralErrorMeters+uncertainty}
        if(competitors.any{!sharedVertexOnly(best,it)})return defer("position_ambiguous")

        val traversed=lengths.take(best.index).sum()+best.match.fraction*best.length
        val remaining=((1.0-traversed/totalLength)*snapshot.distanceMeters).coerceIn(0.0,snapshot.distanceMeters)
        val destination=geometry.last()
        val lastNonzeroSegment=lengths.indexOfLast{it>0.01}
        val withinArrival=best.index==lastNonzeroSegment &&
            geodesicDistanceMeters(user,destination)+accuracy<=arrivalConfig.arrivalRadiusMeters &&
            remaining+accuracy<=arrivalConfig.arrivalRadiusMeters
        if(!withinArrival) {
            arrival=ArrivalGateProgress()
            return Progress(remaining,false,"progress")
        }
        arrival=updateArrivalGate(arrival,destination,user,accuracy.toFloat(),stamp.timestampNanos/1_000_000,arrivalConfig)
        return Progress(remaining,arrival.arrived,if(arrival.arrived)"arrived" else "arrival_confirming")
    }

    /** Two incident segments are one unambiguous location only when both projections hit their shared vertex. */
    private fun sharedVertexOnly(a: Segment,b: Segment): Boolean {
        if(abs(a.index-b.index)!=1)return false
        val before=if(a.index<b.index)a else b
        val after=if(a.index<b.index)b else a
        return geodesicDistanceMeters(before.to,after.from)<=0.01 &&
            (1.0-before.match.fraction)*before.length<=0.05 && after.match.fraction*after.length<=0.05
    }
}
