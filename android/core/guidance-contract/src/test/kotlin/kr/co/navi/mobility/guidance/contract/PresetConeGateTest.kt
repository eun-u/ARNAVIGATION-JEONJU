package kr.co.navi.mobility.guidance.contract

import org.junit.Assert.*
import org.junit.Test

class PresetConeGateTest {
    private val cone=DetectedRegion("traffic_cone",0.9f,NormalizedRegion(.4f,.3f,.6f,.7f))
    private fun stamp(i: Long)=FrameStamp(i,i*250_000_000)
    @Test fun realConeMustPersistAtEligibleSiteAndCanFireOnlyOnce() {
        val g=PresetConeGate()
        for(i in 0L..3L)assertFalse(g.update(stamp(i),true,listOf(cone)))
        assertTrue(g.update(stamp(4),true,listOf(cone)))
        assertFalse(g.update(stamp(5),true,listOf(cone)))
    }
    @Test fun outsideSitePeopleAndSideConesNeverTrigger() {
        for(detection in listOf(cone.copy(label="person"),cone.copy(bounds=NormalizedRegion(.01f,.3f,.10f,.7f)))) {
            val g=PresetConeGate()
            for(i in 0L..12L)assertFalse(g.update(stamp(i),true,listOf(detection)))
        }
        val g=PresetConeGate()
        for(i in 0L..12L)assertFalse(g.update(stamp(i),false,listOf(cone)))
    }
    @Test fun missingFrameGapAndDuplicateCannotBuildPersistence() {
        val g=PresetConeGate()
        for(i in 0L..3L)g.update(stamp(i),true,listOf(cone))
        assertFalse(g.update(stamp(4),true,emptyList()))
        assertFalse(g.update(stamp(5),true,listOf(cone)))
        assertFalse(g.update(stamp(5),true,listOf(cone)))
        assertFalse(g.update(stamp(9),true,listOf(cone)))
        for(i in 10L..12L)assertFalse(g.update(stamp(i),true,listOf(cone)))
        assertTrue(g.update(stamp(13),true,listOf(cone)))
    }
}
