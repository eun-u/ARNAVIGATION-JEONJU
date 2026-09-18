package kr.co.navi.mobility.demo

import java.io.File
import kr.co.navi.mobility.guidance.contract.*
import org.junit.Assert.*
import org.junit.Test

class HackathonCourseTest {
    private fun course()=HackathonCourse.parse(File("src/main/assets/jeonju_hackathon_course.json").readText())
    @Test fun initialRouteFinishesAtUserSelectedSouthernMerge() {
        val c=course();val route=c.initial("test-preset")
        assertEquals(listOf(127.1319938,35.8457996),route.geometry.last())
        assertEquals("OSM_N4655264758",route.destinationNode)
        assertTrue(route.distanceM in 61.0..62.5)
        assertEquals("demo_preset",route.routeType)
        assertEquals(0,route.provenance.verifiedEdges)
    }
    @Test fun detourRetreatsFromActualPositionAndUsesEastPathOnly() {
        val c=course();val user=GeoCoordinate(35.84593,127.131986)
        val route=c.detour("test-preset",user)
        assertEquals(listOf(user.longitude,user.latitude),route.geometry.first())
        assertEquals(c.approach.first(),route.segments.first().geometry.last())
        assertEquals(c.east.geometry.first(),route.segments.last().geometry.first())
        assertEquals(c.east.geometry.last(),route.geometry.last())
        assertEquals(listOf(c.baseline.first().edgeId,c.east.edgeId),route.edgeIds)
        assertFalse(route.edgeIds.contains(c.baseline.last().edgeId))
        assertTrue(route.distanceM in 145.0..154.0)
        assertEquals(2,route.routeRevision)
    }
    @Test fun cannotSwitchAtStartOrFromParallelEasternPath() {
        val c=course()
        assertFalse(c.canSwitch(GeoCoordinate(35.8463514,127.1319861)))
        assertFalse(c.canSwitch(GeoCoordinate(35.84590,127.13208)))
        assertThrows(IllegalArgumentException::class.java){c.detour("test",GeoCoordinate(35.8463514,127.1319861))}
    }
}
