package kr.co.navi.mobility.guidance.contract

import kotlin.math.*

data class PocTurnCue(val vertex: Int,val text: String)

/** A cue comes from the current route geometry and position, never a timer. */
fun nextPocTurnCue(route: List<GeoCoordinate>,user: GeoCoordinate): PocTurnCue? {
    if(route.size<3)return null
    val match=nearestForwardRouteSegment(route.map{listOf(it.longitude,it.latitude)},user,3.0) ?: return null
    val i=match.segmentIndex+1
    if(i>=route.lastIndex)return null
    val corner=route[i]
    val remaining=geodesicDistanceMeters(route[i-1],corner)*(1-match.fraction)
    if(remaining !in 0.5..7.0)return null
    fun bearing(a: GeoCoordinate,b: GeoCoordinate)=atan2((b.longitude-a.longitude)*cos(Math.toRadians(a.latitude)),b.latitude-a.latitude)
    val delta=bearing(corner,route[i+1])-bearing(route[i-1],corner)
    val degrees=Math.toDegrees(atan2(sin(delta),cos(delta)))
    val text=when {
        abs(degrees)>=135 -> "앞에서 돌아서 파란 경로를 따라가세요."
        degrees>=30 -> "앞에서 오른쪽으로 이동하세요."
        degrees<=-30 -> "앞에서 왼쪽으로 이동하세요."
        else -> return null
    }
    return PocTurnCue(i,text)
}
