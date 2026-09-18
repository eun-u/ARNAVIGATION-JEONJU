package kr.co.navi.mobility.demo

import kr.co.navi.mobility.data.remote.NaviApiException
import kr.co.navi.mobility.guidance.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Synthetic contract fixtures, not live inference or field alignment evidence. */
class PocReroutePolicyTest {
    private val origin=GeoCoordinate(35.846,127.132)
    private fun geo(east: Double,north: Double)=GeoCoordinate(origin.latitude+north/111195.08,
        origin.longitude+east/(111195.08*kotlin.math.cos(Math.toRadians(origin.latitude))))
    private fun segment(id: String,a: GeoCoordinate,b: GeoCoordinate)=RouteSegment(id,id,listOf(a,b))
    private val calibration=MapCalibration.fromReferences(
        CalibrationReference(origin,Vec3(0.0,0.0,0.0),0.1,"A"),
        CalibrationReference(geo(10.0,0.0),Vec3(10.0,0.0,0.0),0.1,"B"),0,"calibration-fixture")
    private fun spatial(position: GeoCoordinate=geo(0.0,5.0),accuracy: Double?=.25)=SpatialFrameContext(
        FrameStamp(1,1_000_000_000),LocalPose(0f,0f,-5f,0f,0f,0f,1f),position,PoseAccuracy(horizontalMeters=accuracy),
        TrackingQuality.TRACKING,true,SpatialCapture(CameraIntrinsics(10,10,10.0,10.0,5.0,5.0),
            ImageTransform(0.0,0.0,.1,0.0,0.0,.1),null,null,calibration,0,10_000,"contract_fixture"))
    private val straight=listOf(segment("main",origin,geo(0.0,10.0)))

    @Test fun `missing nonfinite inaccurate or stale current positions defer`() {
        for(accuracy in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,0.0,1.5001)) {
            assertEquals("position_accuracy_insufficient",PocReroutePolicy.positionReason(straight,spatial(accuracy=accuracy),calibration.revision,10_000))
        }
        assertEquals("position_stale",PocReroutePolicy.positionReason(straight,spatial(),calibration.revision,15_001))
        assertEquals("position_stale",PocReroutePolicy.positionReason(straight,spatial(),calibration.revision,8_999))
        assertNull(PocReroutePolicy.positionReason(straight,spatial(),calibration.revision,null)) // Replay retains capture UTC.
        assertEquals("alignment_unavailable",PocReroutePolicy.positionReason(straight,spatial(),"another-calibration",10_000))
    }

    @Test fun `off route parallel competitors are checked and recovery needs a unique scope position`() {
        val fullScope=straight+segment("other",geo(.6,0.0),geo(.6,10.0))
        val ambiguous=spatial(geo(.3,5.0))
        assertNull(PocReroutePolicy.positionReason(straight,ambiguous,calibration.revision,10_000))
        assertEquals("position_ambiguous",PocReroutePolicy.positionReason(fullScope,ambiguous,calibration.revision,10_000))
        assertEquals("position_outside_poc",PocReroutePolicy.positionReason(straight,spatial(geo(5.0,5.0)),calibration.revision,10_000))
        assertNull(PocReroutePolicy.positionReason(straight,spatial(),calibration.revision,10_000))
    }

    @Test fun `shared scope endpoint is a single location`() {
        val fork=straight+segment("branch",origin,geo(10.0,0.0))
        assertNull(PocReroutePolicy.positionReason(fork,spatial(origin),calibration.revision,10_000))
    }

    @Test fun `only explicit spatial rejections may retain current guidance`() {
        for(code in listOf("position_ambiguous","position_outside_poc","position_stale","position_accuracy_insufficient",
            "progress_mismatch","object_position_ambiguous","observation_stale","insufficient_spatial_evidence","edge_impact_mismatch")) {
            assertEquals(code,PocReroutePolicy.deferredReason(NaviApiException(422,code,code)))
        }
        assertNull(PocReroutePolicy.deferredReason(NaviApiException(409,"stale_route_revision","revision changed")))
        assertNull(PocReroutePolicy.deferredReason(NaviApiException(422,"request_validation_failed","bad schema")))
        assertNull(PocReroutePolicy.deferredReason(NaviApiException(500,"position_ambiguous","server error")))
    }

    @Test fun `deferred track can retry after cooldown without per frame or new track request floods`() {
        val gate=PocRerouteAttemptGate(100)
        assertTrue(gate.tryAcquire("edge:observation",0))
        assertFalse(gate.tryAcquire("edge:observation",1))
        gate.defer("edge:observation",10)
        assertFalse(gate.tryAcquire("edge:observation",109))
        assertFalse(gate.tryAcquire("edge:another-track",109))
        assertFalse(gate.cooldownElapsed(109))
        assertTrue(gate.cooldownElapsed(110))
        assertTrue(gate.tryAcquire("edge:observation",110))
        assertFalse(gate.tryAcquire("edge:observation",111)) // Successful requests stay suppressed.
    }

    @Test fun `own deadline becomes a failure without cancelling the enclosing operation`() = runTest {
        val error=runCatching {pocDeadline("initial_route",20){delay(100);true}}.exceptionOrNull()
        assertTrue(error is PocOperationTimeout)
        assertEquals("initial_route_timeout",error?.message)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `user cancellation remains cancellation without cancelling the caller`() = runTest {
        val cancelled=CancellationException("user stop")
        val error=runCatching {pocDeadline("reroute",100){throw cancelled}}.exceptionOrNull()
        // Coroutine stacktrace recovery may copy an exception while preserving its semantics.
        assertTrue(error is CancellationException)
        assertEquals(cancelled.message,error?.message)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `outer deadline cancellation is preserved in the cancelled enclosing scope`() = runTest {
        var enclosingActive: Boolean?=null
        val outer=runCatching {
            withTimeout(10) {
                try {pocDeadline("reroute",100){delay(200)}}
                catch(error: CancellationException) {
                    enclosingActive=currentCoroutineContext().isActive
                    throw error
                }
            }
        }.exceptionOrNull()
        assertTrue(outer is TimeoutCancellationException)
        assertEquals(false,enclosingActive)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `cancelling a parent job stays cancellation inside the operation`() = runTest {
        val parent=Job(coroutineContext[Job])
        var observed: Throwable?=null
        var operationActive: Boolean?=null
        val child=launch(parent,start=CoroutineStart.UNDISPATCHED) {
            try {pocDeadline("reroute",100){awaitCancellation()}}
            catch(error: Throwable) {
                observed=error
                operationActive=currentCoroutineContext().isActive
            }
        }
        parent.cancel(CancellationException("user stop"))
        child.join()
        assertTrue(observed is CancellationException)
        assertEquals("user stop",observed?.message)
        assertEquals(false,operationActive)
        assertTrue(parent.isCancelled)
        assertTrue(child.isCancelled)
        assertTrue(currentCoroutineContext().isActive)
    }
}
