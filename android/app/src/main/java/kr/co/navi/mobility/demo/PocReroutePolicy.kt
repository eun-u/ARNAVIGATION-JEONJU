package kr.co.navi.mobility.demo

import kr.co.navi.mobility.data.remote.NaviApiException
import kr.co.navi.mobility.guidance.contract.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** Request deadlines are failures; cancellation of the host/user job remains cancellation. */
class PocOperationTimeout(operation: String,cause: TimeoutCancellationException) :
    RuntimeException("${operation}_timeout",cause)

suspend fun <T> pocDeadline(operation: String,milliseconds: Long,block: suspend ()->T): T =
    try {withTimeout(milliseconds){block()}}
    catch(error: TimeoutCancellationException){
        currentCoroutineContext().ensureActive() // An enclosing/user cancellation still belongs to the caller.
        throw PocOperationTimeout(operation,error)
    }

/** A rejected observation may be retried from a fresh frame, without a request every frame. */
class PocRerouteAttemptGate(private val cooldownNanos: Long=1_000_000_000L) {
    private val sent=mutableSetOf<String>()
    private var retryAfter=Long.MIN_VALUE
    @Synchronized fun tryAcquire(key: String,nowNanos: Long): Boolean =
        nowNanos>=retryAfter && sent.add(key)
    @Synchronized fun defer(key: String,nowNanos: Long) {
        sent.remove(key)
        retryAfter=nowNanos+cooldownNanos
    }
    @Synchronized fun cooldownElapsed(nowNanos: Long)=nowNanos>=retryAfter
}

object PocReroutePolicy {
    private val transientReasons=setOf("position_ambiguous","position_outside_poc","position_stale",
        "position_accuracy_insufficient","progress_mismatch","object_position_ambiguous",
        "observation_stale","insufficient_spatial_evidence","edge_impact_mismatch")

    fun deferredReason(error: Throwable): String? = (error as? NaviApiException)?.let {
        it.errorCode?.takeIf { reason->it.statusCode==422 && reason in transientReasons }
    }

    /** Same eight physical edges and uncertainty budget as the server, including off-route branches. */
    fun positionReason(segments: List<RouteSegment>,spatial: SpatialFrameContext,
                       calibrationRevision: String?,nowEpochMillis: Long?): String? {
        val capture=spatial.capture ?: return "alignment_unavailable"
        val calibration=capture.calibration ?: return "alignment_unavailable"
        if(spatial.trackingQuality!=TrackingQuality.TRACKING || calibration.revision!=calibrationRevision) return "alignment_unavailable"
        val accuracy=spatial.accuracy?.horizontalMeters
        if(accuracy==null || !accuracy.isFinite() || accuracy<=0 || accuracy>1.5) return "position_accuracy_insufficient"
        if(nowEpochMillis!=null && nowEpochMillis-capture.observedAtEpochMillis !in -1_000L..5_000L) return "position_stale"
        val user=spatial.geoCoordinate ?: return "alignment_unavailable"
        data class Match(val segment: RouteSegment,val distance: Double,val fraction: Double)
        val matches=segments.mapNotNull { segment->
            val geometry=segment.geometry
            val match=nearestForwardRouteSegment(geometry.map{listOf(it.longitude,it.latitude)},user,Double.POSITIVE_INFINITY) ?: return@mapNotNull null
            val lengths=geometry.zipWithNext().map{(a,b)->geodesicDistanceMeters(a,b)}
            val total=lengths.sum()
            if(total<=0)return@mapNotNull null
            Match(segment,match.lateralErrorMeters,(lengths.take(match.segmentIndex).sum()+match.fraction*lengths[match.segmentIndex])/total)
        }.sortedBy{it.distance}
        val best=matches.firstOrNull() ?: return "position_outside_poc"
        if(best.distance>3.0)return "position_outside_poc"
        val nearby=matches.filter{it.distance<=best.distance+accuracy+0.5}
        if(nearby.size>1) {
            val endpoints=nearby.map { match->when {
                match.fraction<0.015->match.segment.geometry.first()
                match.fraction>0.985->match.segment.geometry.last()
                else->null
            }}
            val first=endpoints.first()
            if(first==null || endpoints.any{it==null || geodesicDistanceMeters(first,it)>0.01})return "position_ambiguous"
        }
        return null
    }
}
