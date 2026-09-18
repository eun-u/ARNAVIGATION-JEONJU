package kr.co.navi.mobility.guidance.contract

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class PocStartAlignmentTest {
    private val origin=GeoCoordinate(35.8463514,127.1319861)
    private val south=GeoCoordinate(35.8458917,127.1319832)
    @Test fun southFacingCourseRoundTripsWithoutMeasuredReferences() {
        val a=PocStartAlignment(origin,south,LocalPose(12f,1.5f,7f,0f,0f,0f,1f),100,"poc-start-test")
        assertEquals(origin,a.geo(Vec3(12.0,1.5,7.0)))
        val w=a.world(south,0.0)
        assertTrue(w.z<7);assertEquals(12.0,w.x,0.001)
        val back=a.geo(w)
        assertEquals(south.latitude,back.latitude,1e-9);assertEquals(south.longitude,back.longitude,1e-9)
        assertEquals("poc_start",a.source)
        assertTrue(a.errorAt(w,600_000_000_101).isInfinite())
    }
    @Test fun rotatedAnchorKeepsSameMapRoute() {
        val h=sqrt(.5).toFloat()
        val a=PocStartAlignment(origin,south,LocalPose(0f,0f,0f,0f,h,0f,h),0,"poc-start-test")
        val w=a.world(south,0.0)
        assertTrue(w.x< -50);assertEquals(0.0,w.z,.001)
        val moved=a.copy(anchorPose=a.anchorPose.copy(xMeters=5f,zMeters=4f))
        assertEquals(w.x+5,moved.world(south,0.0).x,.001)
        assertEquals(w.z+4,moved.world(south,0.0).z,.001)
    }
    @Test(expected=IllegalArgumentException::class) fun downwardPhoneCannotDefineHeading() {
        val h=sqrt(.5).toFloat()
        PocStartAlignment(origin,south,LocalPose(0f,0f,0f,h,0f,0f,h),0,"poc-start-test")
    }
}
