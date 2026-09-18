package kr.co.navi.mobility.guidance.contract

import kotlin.math.cos
import org.junit.Assert.*
import org.junit.Test

/** Pure contract geometry only; these inputs do not establish any field accuracy. */
class PocNavigationProgressTest {
    private val origin=GeoCoordinate(35.845,127.131)
    private fun geo(east: Double,north: Double)=GeoCoordinate(origin.latitude+north/111195.08,origin.longitude+east/(111195.08*cos(Math.toRadians(origin.latitude))))
    private val calibration=MapCalibration.fromReferences(
        CalibrationReference(origin,Vec3(0.0,1.4,0.0),0.01,"contract A"),
        CalibrationReference(geo(0.0,10.0),Vec3(0.0,1.4,-10.0),0.01,"contract B"),0,"contract-cal")
    private fun route(points: List<GeoCoordinate> = listOf(origin,geo(0.0,10.0),geo(0.0,20.0)),distance: Double=20.0,revision: Int=1)=RouteSnapshot(
        revision,1,"scope","dataset","session",points,listOf(RouteSegment("edge","edge",points)),distance)
    private fun spatial(id: Long,millis: Long,north: Double=19.5,east: Double=0.0,accuracy: Double=0.2,epoch: Long=1,cal: MapCalibration=calibration): SpatialFrameContext {
        val user=geo(east,north)
        val world=cal.world(user,1.4)
        return SpatialFrameContext(FrameStamp(id,millis*1_000_000),LocalPose(world.x.toFloat(),world.y.toFloat(),world.z.toFloat(),0f,0f,0f,1f),user,
            PoseAccuracy(accuracy),TrackingQuality.TRACKING,false,
            SpatialCapture(CameraIntrinsics(100,100,100.0,100.0,50.0,50.0),ImageTransform(0.0,0.0,0.01,0.0,0.0,0.01),null,null,cal,0,0,"contract_test",trackingEpoch=epoch))
    }

    @Test fun remainingDistanceUsesActualSnapshotLengthAndSharedVertexIsNotAmbiguous() {
        val tracker=PocNavigationProgressTracker()
        val midway=tracker.update(route(distance=40.0),spatial(1,0,north=5.0))
        assertEquals(30.0,requireNotNull(midway.remainingMeters),0.01)
        assertFalse(midway.arrived)
        val vertex=tracker.update(route(distance=40.0),spatial(2,500,north=10.0))
        assertEquals(20.0,requireNotNull(vertex.remainingMeters),0.01)
        assertEquals("progress",vertex.reason)
    }

    @Test fun arrivalRequiresContinuousAccurateEvidenceSpanningTwoSeconds() {
        val tracker=PocNavigationProgressTracker()
        repeat(4){i->assertFalse(tracker.update(route(),spatial(i.toLong(),i*500L)).arrived)}
        val arrived=tracker.update(route(),spatial(4,2000))
        assertTrue(arrived.arrived)
        assertEquals("arrived",arrived.reason)
    }

    @Test fun badAlignmentAndUncertaintyOutsideDestinationRadiusNeverArrive() {
        for(kind in listOf("tracking","missing_calibration","accuracy","radius")) {
            val tracker=PocNavigationProgressTracker()
            repeat(6){i->
                val baseline=spatial(i.toLong(),i*500L,north=if(kind=="radius")18.3 else 19.5,accuracy=if(kind=="accuracy")1.6 else if(kind=="radius")0.4 else 0.2)
                val s=when(kind){
                    "tracking"->baseline.copy(trackingQuality=TrackingQuality.DEGRADED)
                    "missing_calibration"->baseline.copy(capture=baseline.capture?.copy(calibration=null))
                    else->baseline
                }
                assertFalse(kind,tracker.update(route(),s).arrived)
            }
        }
    }

    @Test fun gapsDuplicateIdsAndReversedTimeResetDwell() {
        for(kind in listOf("gap","duplicate_id","reversed_time")) {
            val tracker=PocNavigationProgressTracker()
            repeat(4){i->tracker.update(route(),spatial(i.toLong(),i*500L))}
            val invalid=when(kind){
                "gap"->spatial(4,2100)
                "duplicate_id"->spatial(3,2000)
                else->spatial(4,1400)
            }
            val result=tracker.update(route(),invalid)
            assertFalse(kind,result.arrived)
            assertNull(result.remainingMeters)
            assertFalse(kind,tracker.update(route(),spatial(5,2500)).arrived)
        }
    }

    @Test fun routeCalibrationAndTrackingEpochChangesDiscardPreviousDwell() {
        for(kind in listOf("route","calibration","epoch")) {
            val tracker=PocNavigationProgressTracker()
            repeat(4){i->tracker.update(route(),spatial(i.toLong(),i*500L))}
            val changedRoute=route(revision=if(kind=="route")2 else 1)
            val s=spatial(4,2000,epoch=if(kind=="epoch")2 else 1,cal=if(kind=="calibration")calibration.copy(revision="new-cal") else calibration)
            assertFalse(kind,tracker.update(changedRoute,s).arrived)
        }
    }

    @Test fun parallelLegsAndEarlierLegNearDestinationCannotTriggerArrival() {
        val parallel=route(listOf(origin,geo(0.0,20.0),geo(0.6,20.0),geo(0.6,0.0)),40.6)
        val tracker=PocNavigationProgressTracker()
        val result=tracker.update(parallel,spatial(1,0,north=10.0))
        assertNull(result.remainingMeters)
        assertEquals("position_ambiguous",result.reason)
        val loop=route(listOf(origin,geo(0.0,20.0),geo(10.0,20.0),geo(10.0,0.0),geo(0.0,0.5)),60.0,2)
        repeat(6){i->assertFalse(tracker.update(loop,spatial(i.toLong()+2,i*500L,north=1.0)).arrived)}
        val outside=PocNavigationProgressTracker().update(route(),spatial(1,0,north=10.0,east=5.0))
        assertEquals("position_outside_route",outside.reason)
        assertNull(outside.remainingMeters)
    }
}
