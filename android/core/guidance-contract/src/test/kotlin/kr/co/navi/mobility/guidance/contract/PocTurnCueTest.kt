package kr.co.navi.mobility.guidance.contract
import org.junit.Assert.*
import org.junit.Test

class PocTurnCueTest {
    private val a=GeoCoordinate(35.0,127.0)
    private fun point(n:Double,e:Double)=GeoCoordinate(a.latitude+n/111195.08,a.longitude+e/(111195.08*kotlin.math.cos(Math.toRadians(a.latitude))))
    @Test fun approachingCornerAnnouncesDirectionAndFarAwayDoesNot() {
        val route=listOf(a,point(20.0,0.0),point(20.0,20.0))
        assertNull(nextPocTurnCue(route,a))
        assertEquals("앞에서 오른쪽으로 이동하세요.",nextPocTurnCue(route,point(15.0,0.0))?.text)
        assertNull(nextPocTurnCue(route,point(15.0,10.0)))
        assertEquals("앞에서 왼쪽으로 이동하세요.",nextPocTurnCue(listOf(a,point(20.0,0.0),point(20.0,-20.0)),point(15.0,0.0))?.text)
    }
}
