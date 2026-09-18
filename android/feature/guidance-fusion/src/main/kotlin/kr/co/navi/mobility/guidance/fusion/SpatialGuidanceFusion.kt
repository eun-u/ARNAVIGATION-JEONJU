package kr.co.navi.mobility.guidance.fusion

import kr.co.navi.mobility.guidance.contract.*
import kotlin.math.*

/** Shared Live/Replay decision rules. Tracks use stable map metres after per-frame Anchor alignment. */
class SpatialGuidanceFusion : GuidanceFusion {
    private data class Track(val id: String,val label: String,var origin: Vec3,var last: Vec3,var firstNs: Long,var lastNs: Long)
    private val tracks=mutableListOf<Track>()
    private var nextId=1L
    private var revision: String?=null
    var lastReason="waiting_spatial_input"
        private set

    override fun fuse(spatialContext: SpatialFrameContext,perceptionResult: PerceptionResult): List<HazardObservation> {
        require(spatialContext.stamp==perceptionResult.stamp)
        val c=spatialContext.capture
        val pose=spatialContext.localPose
        val calibration=c?.calibration
        val depth=c?.depth
        val semantics=c?.semantics
        fun defer(reason: String): List<HazardObservation> { lastReason=reason;tracks.clear();return emptyList() }
        if (c==null || pose==null || calibration==null || spatialContext.trackingQuality!=TrackingQuality.TRACKING) return defer("alignment_unavailable")
        if (revision!=calibration.revision) {tracks.clear();revision=calibration.revision}
        val relative=calibration is PocStartAlignment
        if (semantics==null || (depth==null && !relative)) return defer("depth_or_semantics_missing")
        val time=spatialContext.stamp.timestampNanos
        val freshDepth=depth?.takeIf{abs(time-it.timestampNanos)<=100_000_000}
        if ((!relative && freshDepth==null) || abs(time-semantics.timestampNanos)>100_000_000) return defer("spatial_timestamp_mismatch")
        if (calibration.errorAt(pose.position(),time)>1.5) return defer("alignment_uncertain")
        tracks.removeAll { time-it.lastNs>500_000_000 || time<=it.lastNs }
        val assigned=mutableSetOf<String>()
        lastReason="no_supported_obstacle"
        return perceptionResult.detections.mapNotNull { detection ->
            if (detection.confidence<0.6f || detection.label !in setOf("person","bicycle","car","motorcycle","bus","truck","traffic_cone")) return@mapNotNull null
            val bounds=detection.bounds
            val groundPoint=if(relative && detection.label=="traffic_cone") groundContact(pose,c,bounds) else null
            // Sample the object's lower central region; median of valid, confident raw depth values.
            val samples=mutableListOf<Pair<Vec3,Double>>()
            for (iy in 0..2) for (ix in 0..2) {
                val depth=freshDepth ?: continue
                val u=bounds.left+(bounds.right-bounds.left)*(0.3+ix*0.2)
                val v=bounds.top+(bounds.bottom-bounds.top)*(0.55+iy*0.12)
                val pixel=unrotate(u,v,c.rotationDegrees,c.intrinsics)
                val uv=c.imageToTexture.uv(pixel.first,pixel.second)
                if (uv.first !in 0.0..<1.0 || uv.second !in 0.0..<1.0) continue
                val idx=(uv.second*depth.height).toInt()*depth.width+(uv.first*depth.width).toInt()
                val mm=depth.millimeters.getOrNull(idx) ?: continue
                if (mm !in 300..12000 || (depth.confidence.getOrNull(idx)?.toInt()?.and(255) ?: 0)<128) continue
                val meters=mm/1000.0
                samples+=pose.transform(Vec3((pixel.first-c.intrinsics.cx)*meters/c.intrinsics.fx,-(pixel.second-c.intrinsics.cy)*meters/c.intrinsics.fy,-meters)) to meters
            }
            if (samples.size<4 && groundPoint==null) {lastReason="insufficient_depth_samples";return@mapNotNull null}
            val sorted=samples.sortedBy { it.second }
            val rawValid=sorted.size>=4 && sorted.last().second-sorted.first().second<=0.8
            if(!rawValid && groundPoint==null){lastReason="depth_discontinuity";return@mapNotNull null}
            val usePlane=!rawValid
            val (world,distance)=if(usePlane)requireNotNull(groundPoint) else sorted[sorted.size/2]
            val error=calibration.errorAt(world,time)+if(usePlane)0.15+distance*0.03 else 0.10+distance*0.015
            if (error>1.5) {lastReason="object_position_uncertain";return@mapNotNull null}
            // The ground just below the object must contain confident sidewalk pixels.
            var sidewalk=0;var examined=0
            for (ix in 0..4) {
                val u=bounds.left+(bounds.right-bounds.left)*(0.1+ix*0.2)
                val v=(bounds.bottom+0.025).coerceAtMost(0.995).toDouble()
                val pixel=unrotate(u,v,c.rotationDegrees,c.intrinsics)
                val uv=c.imageToTexture.uv(pixel.first,pixel.second)
                if (uv.first !in 0.0..<1.0 || uv.second !in 0.0..<1.0) continue
                val idx=(uv.second*semantics.height).toInt()*semantics.width+(uv.first*semantics.width).toInt()
                if ((semantics.confidence[idx].toInt() and 255)>=153) {
                    examined++
                    val label=semantics.labels[idx].toInt() and 255
                    if (label==5 || (relative && label==6)) sidewalk++ // SIDEWALK; TERRAIN only in fixed-course PoC
                }
            }
            if (examined<3 || sidewalk.toDouble()/examined<0.6) {lastReason="outside_sidewalk_or_mask_uncertain";return@mapNotNull null}
            val mapPoint=calibration.mapMeters(world)
            val possible=tracks.filter { it.id !in assigned && it.label==detection.label && horizontal(it.last,mapPoint)<0.8 }.sortedBy { horizontal(it.last,mapPoint) }
            if (possible.size>1 && horizontal(possible[1].last,mapPoint)-horizontal(possible[0].last,mapPoint)<0.2) {
                lastReason="track_ambiguous";return@mapNotNull null
            }
            val track=possible.firstOrNull() ?: Track("spatial-${nextId++}",detection.label,mapPoint,mapPoint,time,time).also(tracks::add)
            assigned+=track.id
            val elapsed=(time-track.lastNs)/1e9
            val moving=(elapsed>0 && horizontal(track.last,mapPoint)/elapsed>0.4) || horizontal(track.origin,mapPoint)>0.45
            if (moving) {track.firstNs=time;track.origin=mapPoint}
            track.last=mapPoint;track.lastNs=time
            val duration=(time-track.firstNs)/1_000_000
            if (moving || duration<2000) {lastReason=if(moving) "moving_object" else "persistence_pending";return@mapNotNull null}
            lastReason="stationary_sidewalk_observation"
            HazardObservation("${calibration.revision}-${track.id}",spatialContext.stamp,"stationary_obstacle",calibration.geo(world),if(calibration is MapCalibration)error else null,distance,null,
                duration,false,detection.confidence,perceptionResult.modelVersion,ObservationEvidence(listOf(detection.label),listOf(track.id),
                    if(usePlane)"ground_plane_ray" else "raw_depth",if(usePlane)c.groundHeightMeters else null,
                    if(relative)"sidewalk_or_terrain" else "sidewalk"),calibration.revision,true,calibration.source,if(calibration is PocStartAlignment)error else null)
        }
    }
    /** Only a currently detected horizontal plane; no assumed camera height or image-centre proxy. */
    private fun groundContact(pose: LocalPose,c: SpatialCapture,b: NormalizedRegion): Pair<Vec3,Double>? {
        val y=c.groundHeightMeters?.takeIf{it.isFinite()} ?: return null
        if(pose.yMeters-y !in 0.4..2.5 || b.bottom>=0.98f)return null
        val p=unrotate((b.left+b.right)/2.0,b.bottom.toDouble(),c.rotationDegrees,c.intrinsics)
        val end=pose.transform(Vec3((p.first-c.intrinsics.cx)/c.intrinsics.fx,-(p.second-c.intrinsics.cy)/c.intrinsics.fy,-1.0))
        val dy=end.y-pose.yMeters
        if(dy>=-0.1)return null
        val t=(y-pose.yMeters)/dy
        val point=Vec3(pose.xMeters+(end.x-pose.xMeters)*t,y,pose.zMeters+(end.z-pose.zMeters)*t)
        val distance=horizontal(point,pose.position())
        return if(distance in 0.3..8.0)point to distance else null
    }
    private fun horizontal(a: Vec3,b: Vec3)=hypot(a.x-b.x,a.z-b.z)
    private fun unrotate(u: Double,v: Double,r: Int,k: CameraIntrinsics): Pair<Double,Double> {
        val xy=when(r) {90->v to 1-u;180->1-u to 1-v;270->1-v to u;else->u to v}
        return xy.first*k.width to xy.second*k.height
    }
}

/** Reject nearby parallel paths and objects outside the planned travel corridor. */
class RouteImpactMapper(private val allSegments: List<RouteSegment>) {
    fun match(observation: HazardObservation,snapshot: RouteSnapshot,user: GeoCoordinate): EdgeImpact? {
        val point=observation.geoCoordinate ?: return null
        val error=observation.navigationBudgetMeters() ?: return null
        if (observation.dynamic || !observation.corridorOccupied || !error.isFinite() || error<0.0 || error>1.5) return null
        val matches=allSegments.mapNotNull { segment ->
            nearestForwardRouteSegment(segment.geometry.map{listOf(it.longitude,it.latitude)},point,3.0)?.let { segment to it }
        }.sortedBy{it.second.lateralErrorMeters}
        val best=matches.firstOrNull() ?: return null
        // A centreline corridor is a demo intervention policy, not an inferred physical width.
        if (best.second.lateralErrorMeters+error>1.25) return null
        if (matches.drop(1).any { it.first.physicalSegmentId!=best.first.physicalSegmentId && it.second.lateralErrorMeters <= best.second.lateralErrorMeters+error+0.5 }) return null
        val routeIndex=snapshot.segments.indexOfFirst{it.edgeId==best.first.edgeId}
        if (routeIndex<0) return null
        val (userIndex,userPosition)=snapshot.segments.mapIndexedNotNull { i,s -> routePosition(s.geometry,user)?.let{i to it} }
            .minByOrNull{it.second.lateralErrorMeters} ?: return null
        if (routeIndex<userIndex) return null
        val objectPosition=routePosition(snapshot.segments[routeIndex].geometry,point) ?: return null
        // Original graph edges may extend behind a clipped start or beyond a clipped destination.
        // Endpoint clamping must not turn those observations into an object on the current route.
        if(objectPosition.alongMeters<0.0 || objectPosition.alongMeters>objectPosition.lengthMeters) return null
        val interveningLength=snapshot.segments.subList(userIndex,routeIndex).sumOf { segment ->
            segment.geometry.zipWithNext().sumOf{(a,b)->geodesicDistanceMeters(a,b)}
        }
        if(interveningLength+objectPosition.alongMeters-userPosition.alongMeters<=error) return null
        return EdgeImpact(best.first.edgeId,best.first.physicalSegmentId,observation,best.second.lateralErrorMeters)
    }

    private data class RoutePosition(val alongMeters: Double,val lengthMeters: Double,val lateralErrorMeters: Double)

    private fun routePosition(geometry: List<GeoCoordinate>,point: GeoCoordinate): RoutePosition? {
        val match=nearestForwardRouteSegment(geometry.map{listOf(it.longitude,it.latitude)},point,3.0) ?: return null
        val lengths=geometry.zipWithNext().map{(a,b)->geodesicDistanceMeters(a,b)}
        val from=geometry[match.segmentIndex];val to=geometry[match.segmentIndex+1]
        fun local(p: GeoCoordinate): Pair<Double,Double> {
            val east=(p.longitude-point.longitude)*cos(Math.toRadians((p.latitude+point.latitude)*0.5))
            return east to p.latitude-point.latitude
        }
        val start=local(from);val end=local(to)
        val dx=end.first-start.first;val dy=end.second-start.second
        val rawFraction=-(start.first*dx+start.second*dy)/(dx*dx+dy*dy)
        val first=lengths.indexOfFirst{it>0.01};val last=lengths.indexOfLast{it>0.01}
        val fraction=when {
            match.segmentIndex==first && rawFraction<0.0 -> rawFraction
            match.segmentIndex==last && rawFraction>1.0 -> rawFraction
            else -> match.fraction
        }
        return RoutePosition(lengths.take(match.segmentIndex).sum()+fraction*lengths[match.segmentIndex],lengths.sum(),match.lateralErrorMeters)
    }
}
