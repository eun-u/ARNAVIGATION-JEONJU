package kr.co.navi.mobility.ai.cone

import org.junit.Assert.*
import org.junit.Test

class ConeShapeDetectorTest {
    private fun pixels(cone: Boolean, stripe: Boolean): IntArray = IntArray(120*160) {i->
        val x=i%120;val y=i/120
        val half=if(cone)(y-30)/4 else 15
        when {
            y !in 30..115 || kotlin.math.abs(x-60)>half -> 0xff242424.toInt()
            stripe && y in 55..65 -> 0xffdddddd.toInt()
            else -> 0xffee4510.toInt()
        }
    }
    @Test fun aStripedWideningConeProducesOnlyImageSpaceObservation() {
        val result=ConeShapeDetector().detect(pixels(true,true),120,160)
        assertEquals(1,result.size)
        assertEquals("traffic_cone",result.single().label)
        assertTrue(result.single().confidence>=0.6f)
    }
    @Test fun colourAloneAndStripedRectanglesAreNotCones() {
        assertTrue(ConeShapeDetector().detect(pixels(true,false),120,160).isEmpty())
        assertTrue(ConeShapeDetector().detect(pixels(false,true),120,160).isEmpty())
    }
}
