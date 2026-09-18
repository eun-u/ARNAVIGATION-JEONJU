package kr.co.navi.mobility.demo

import org.junit.Assert.*
import org.junit.Test

class CaseCollectionPlanTest {
    @Test fun `each timestamp belongs to exactly one window and completion has no case label`() {
        assertNull(CaseCollectionPlan.stepAt(-1))
        assertEquals("C01",CaseCollectionPlan.stepAt(0)?.id)
        assertEquals("C01",CaseCollectionPlan.stepAt(59_999)?.id)
        assertEquals("C02",CaseCollectionPlan.stepAt(60_000)?.id)
        assertEquals("C03",CaseCollectionPlan.stepAt(120_000)?.id)
        assertEquals("C03",CaseCollectionPlan.stepAt(179_999)?.id)
        assertNull(CaseCollectionPlan.stepAt(180_000))
        assertNull(CaseCollectionPlan.stepAt(Long.MAX_VALUE))
    }
    @Test fun `countdown does not finish early or become negative`() {
        assertEquals(180,CaseCollectionPlan.remainingSeconds(0))
        assertEquals(1,CaseCollectionPlan.remainingSeconds(179_999))
        assertEquals(0,CaseCollectionPlan.remainingSeconds(180_000))
        assertEquals(0,CaseCollectionPlan.remainingSeconds(200_000))
    }
}
