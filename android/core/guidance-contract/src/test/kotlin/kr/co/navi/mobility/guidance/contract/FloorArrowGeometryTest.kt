package kr.co.navi.mobility.guidance.contract

import org.junit.Assert.*
import org.junit.Test

class FloorArrowGeometryTest {
    @Test fun arrowsAreSeparateFlatTrianglesPointingAlongEachSegment() {
        val p=floorArrowTriangles(listOf(Vec3(0.0,-1.2,0.0),Vec3(0.0,-1.2,-6.0),Vec3(6.0,-1.2,-6.0)))
        assertTrue(p.isNotEmpty());assertEquals(0,p.size%27)
        for(i in p.indices step 3)assertEquals(-1.2f,p[i+1],.0001f)
        val arrows=p.toList().chunked(27)
        assertTrue(arrows.first()[2]<arrows.first()[5])
        assertTrue(arrows.last()[0]>arrows.last()[3])
        assertTrue(arrows.size in 4..6)
        assertTrue(arrows[1][2]<arrows[0][2]-1.5f)
    }
    @Test fun duplicateAndShortPointsDoNotProduceInvalidTriangles() {
        val a=Vec3(1.0,0.0,1.0)
        assertEquals(0,floorArrowTriangles(listOf(a,a)).size)
        assertEquals(0,floorArrowTriangles(listOf(a,Vec3(1.0,0.0,1.2))).size)
    }
}
