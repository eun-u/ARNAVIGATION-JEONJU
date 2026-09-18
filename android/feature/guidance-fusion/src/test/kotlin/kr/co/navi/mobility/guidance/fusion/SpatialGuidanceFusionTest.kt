package kr.co.navi.mobility.guidance.fusion

import kr.co.navi.mobility.guidance.contract.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Synthetic geometry/observation contracts only; no scene inference or field accuracy claims. */
class SpatialGuidanceFusionTest {
    private val geo=GeoCoordinate(35.845,127.131)
    private val calibration=MapCalibration.fromReferences(
        CalibrationReference(geo,Vec3(0.0,1.4,0.0),0.01,"test A"),
        CalibrationReference(GeoCoordinate(geo.latitude+10/111195.08,geo.longitude),Vec3(0.0,1.4,-10.0),0.01,"test B"),
        1_000_000_000,"contract-only")
    private fun spatial(frame: Int,sidewalk: Boolean=true,depth: Boolean=true,poseX: Float=0f): SpatialFrameContext {
        val ns=1_000_000_000L+frame*200_000_000L
        val pose=LocalPose(poseX,1.4f,0f,0f,0f,0f,1f)
        return SpatialFrameContext(FrameStamp(frame.toLong(),ns),pose,calibration.geo(pose.position()),PoseAccuracy(0.1),TrackingQuality.TRACKING,depth,
            SpatialCapture(CameraIntrinsics(100,100,100.0,100.0,50.0,50.0),ImageTransform(0.0,0.0,0.01,0.0,0.0,0.01),
                if(depth)DepthSamples(100,100,IntArray(10000){5000},ByteArray(10000){255.toByte()},ns) else null,
                SemanticSamples(100,100,ByteArray(10000){if(sidewalk)5 else 4},ByteArray(10000){255.toByte()},ns),calibration,0,0,"contract_test"))
    }
    private fun detection(s: SpatialFrameContext)=PerceptionResult(s.stamp,"test-model",1,listOf(DetectedRegion("bicycle",0.9f,NormalizedRegion(.3f,.2f,.7f,.7f))))
    @Test fun sustainedStationaryRequiresTwoSecondsAndMeasuredSpatialData() {
        val fusion=SpatialGuidanceFusion()
        repeat(10){i->val s=spatial(i);assertTrue(fusion.fuse(s,detection(s)).isEmpty())}
        val s=spatial(10);val o=fusion.fuse(s,detection(s)).single()
        assertEquals(2000,o.persistenceMillis);assertFalse(o.dynamic);assertTrue(o.corridorOccupied)
        assertTrue(requireNotNull(o.geoCoordinate).latitude>geo.latitude)
        val missing=spatial(11,depth=false)
        assertTrue(fusion.fuse(missing,detection(missing)).isEmpty())
    }
    @Test fun roadObjectsAndMovingPeopleNeverTriggerAvoidance() {
        for(kind in listOf("road","moving","brief")) {
            val fusion=SpatialGuidanceFusion();val results=mutableListOf<HazardObservation>()
            repeat(if(kind=="brief")5 else 20){i->val s=spatial(i,sidewalk=kind!="road",poseX=if(kind=="moving")i*0.15f else 0f);results+=fusion.fuse(s,detection(s))}
            assertTrue(kind,results.isEmpty())
        }
    }
    @Test fun gapAndCalibrationLossResetPersistence() {
        val f=SpatialGuidanceFusion()
        repeat(9){i->val s=spatial(i);f.fuse(s,detection(s))}
        val gap=spatial(15);assertTrue(f.fuse(gap,detection(gap)).isEmpty())
        val loss=spatial(16).let{it.copy(capture=it.capture?.copy(calibration=null))}
        assertTrue(f.fuse(loss,detection(loss)).isEmpty())
    }
    @Test fun conesNeedSustainedMeasuredSidewalkEvidenceAndRejectOutsideCones() {
        fun cone(s: SpatialFrameContext)=detection(s).let {r->r.copy(detections=r.detections.map{it.copy(label="traffic_cone")})}
        val onPath=SpatialGuidanceFusion()
        repeat(10){i->val s=spatial(i);assertTrue(onPath.fuse(s,cone(s)).isEmpty())}
        val current=spatial(10)
        assertEquals(listOf("traffic_cone"),onPath.fuse(current,cone(current)).single().evidence.labels)
        for(kind in listOf("outside","missing_alignment","missing_depth")) {
            val fusion=SpatialGuidanceFusion()
            repeat(15) {i->
                val s=spatial(i,sidewalk=kind!="outside",depth=kind!="missing_depth").let {
                    if(kind=="missing_alignment")it.copy(capture=it.capture?.copy(calibration=null)) else it
                }
                assertTrue(kind,fusion.fuse(s,cone(s)).isEmpty())
            }
        }
    }
    @Test fun parallelEdgesAndObjectsBehindProgressAreRejected() {
        val end=GeoCoordinate(geo.latitude+20/111195.08,geo.longitude)
        val seg=RouteSegment("first","first",listOf(geo,end))
        val parallel=RouteSegment("parallel","parallel",listOf(GeoCoordinate(geo.latitude,geo.longitude+0.000005),GeoCoordinate(end.latitude,end.longitude+0.000005)))
        val snapshot=RouteSnapshot(1,0,"scope","dataset","session",seg.geometry,listOf(seg),20.0)
        val observation=HazardObservation("o",FrameStamp(1,1),"stationary",GeoCoordinate(geo.latitude+5/111195.08,geo.longitude),0.2,5.0,null,2200,false,.9f,"test",ObservationEvidence(listOf("bicycle"),listOf("track")),"cal",true)
        assertNotNull(RouteImpactMapper(listOf(seg)).match(observation,snapshot,geo))
        assertNull(RouteImpactMapper(listOf(seg,parallel)).match(observation,snapshot,geo))
        assertNull(RouteImpactMapper(listOf(seg)).match(observation,snapshot,end))
    }
    @Test fun clippedRouteStartRejectsBehindAndUncertainProgressInEitherDirection() {
        fun north(meters: Double)=GeoCoordinate(geo.latitude+meters/111195.08,geo.longitude)
        val original=RouteSegment("first","first",listOf(north(0.0),north(20.0)))
        val mapper=RouteImpactMapper(listOf(original))
        for(direction in listOf(1,-1)) {
            val clipped=original.copy(geometry=listOf(north(10.0),north(if(direction==1)20.0 else 0.0)))
            val snapshot=RouteSnapshot(1,0,"scope","dataset","session",clipped.geometry,listOf(clipped),10.0)
            fun observation(meters: Double)=HazardObservation("o",FrameStamp(1,1),"stationary",north(meters),0.2,5.0,null,2200,false,.9f,"test",ObservationEvidence(listOf("bicycle"),listOf("track")),"cal",true)
            assertNotNull("ahead in direction $direction",mapper.match(observation(10.0+direction*5.0),snapshot,north(10.0)))
            assertNull("behind clipped start in direction $direction",mapper.match(observation(10.0-direction),snapshot,north(10.0)))
            assertNull("same position in direction $direction",mapper.match(observation(10.0),snapshot,north(10.0)))
            assertNull("uncertainty overlaps user in direction $direction",mapper.match(observation(10.0+direction*0.1),snapshot,north(10.0)))
        }
    }
    @Test fun differentEdgeMustLieInsideItsClippedRouteGeometry() {
        fun north(meters: Double)=GeoCoordinate(geo.latitude+meters/111195.08,geo.longitude)
        val first=RouteSegment("first","first",listOf(north(0.0),north(10.0)))
        val second=RouteSegment("second","second",listOf(north(10.0),north(30.0)))
        val clipped=second.copy(geometry=listOf(north(10.0),north(20.0)))
        val snapshot=RouteSnapshot(1,0,"scope","dataset","session",listOf(north(0.0),north(10.0),north(20.0)),listOf(first,clipped),20.0)
        val mapper=RouteImpactMapper(listOf(first,second))
        fun observation(meters: Double)=HazardObservation("o",FrameStamp(1,1),"stationary",north(meters),0.2,5.0,null,2200,false,.9f,"test",ObservationEvidence(listOf("bicycle"),listOf("track")),"cal",true)
        assertEquals("second",mapper.match(observation(15.0),snapshot,north(0.0))?.edgeId)
        assertNull("original edge continues beyond the current destination",mapper.match(observation(21.0),snapshot,north(0.0)))
        assertNull("earlier edge is behind progress",mapper.match(observation(5.0),snapshot,north(15.0)))
    }
    @Test fun twoReferencesDetermineTranslationAndYawAndRejectBadScale() {
        assertEquals(calibration.second.geo.latitude,calibration.geo(calibration.second.world).latitude,1e-9)
        assertEquals(calibration.second.world.z,calibration.world(calibration.second.geo,1.4).z,1e-7)
        assertThrows(IllegalArgumentException::class.java) {
            MapCalibration.fromReferences(calibration.first,calibration.second.copy(world=Vec3(0.0,1.4,-50.0)),1,"bad")
        }
    }
    @Test fun trackedAnchorRebasePreservesMapPositionRevisionAndAge() {
        val theta=0.7
        fun moved(v: Vec3)=Vec3(3.0+v.x*cos(theta)+v.z*sin(theta),v.y,-2.0-v.x*sin(theta)+v.z*cos(theta))
        val updated=calibration.withTrackedReferences(moved(calibration.first.world),moved(calibration.second.world))
        val point=Vec3(0.4,0.0,-4.0)
        assertEquals(calibration.revision,updated.revision)
        assertEquals(calibration.createdTimestampNanos,updated.createdTimestampNanos)
        assertEquals(calibration.geo(point).latitude,updated.geo(moved(point)).latitude,1e-9)
        assertEquals(calibration.geo(point).longitude,updated.geo(moved(point)).longitude,1e-9)
        assertEquals(calibration.mapMeters(point).x,updated.mapMeters(moved(point)).x,1e-9)
        assertEquals(calibration.errorAt(point,2_000_000_000),updated.errorAt(moved(point),2_000_000_000),1e-9)
        assertThrows(IllegalArgumentException::class.java) {
            calibration.withTrackedReferences(calibration.first.world,Vec3(0.0,1.4,-15.0))
        }
    }
    @Test fun stationaryObjectPersistsAcrossPerFrameAnchorWorldCorrections() {
        val fusion=SpatialGuidanceFusion()
        var observation: HazardObservation?=null
        repeat(11) {i->
            val s=rebasedSpatial(i)
            observation=fusion.fuse(s,detection(s)).firstOrNull() ?: observation
        }
        assertNotNull("AR world corrections must not be interpreted as object motion",observation)
        assertEquals(2000,requireNotNull(observation).persistenceMillis)
    }
    @Test fun realMotionRemainsMotionWhileAnchorWorldChanges() {
        val fusion=SpatialGuidanceFusion()
        repeat(20) {i->
            val s=rebasedSpatial(i,i*0.15f)
            assertTrue(fusion.fuse(s,detection(s)).isEmpty())
        }
    }
    private fun rebasedSpatial(frame: Int,relativeMotion: Float=0f): SpatialFrameContext {
        val original=spatial(frame,poseX=relativeMotion)
        val theta=frame*0.04
        fun moved(v: Vec3)=Vec3(frame*0.15+v.x*cos(theta)+v.z*sin(theta),v.y,frame*0.1-v.x*sin(theta)+v.z*cos(theta))
        val updated=calibration.withTrackedReferences(moved(calibration.first.world),moved(calibration.second.world))
        val location=moved(requireNotNull(original.localPose).position())
        val pose=LocalPose(location.x.toFloat(),location.y.toFloat(),location.z.toFloat(),0f,sin(theta/2).toFloat(),0f,cos(theta/2).toFloat())
        return original.copy(localPose=pose,geoCoordinate=updated.geo(location),capture=original.capture?.copy(calibration=updated))
    }
}
