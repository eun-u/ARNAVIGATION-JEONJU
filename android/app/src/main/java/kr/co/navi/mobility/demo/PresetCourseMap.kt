package kr.co.navi.mobility.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kr.co.navi.mobility.guidance.contract.*
import kotlin.math.cos

/** Offline north-up course diagram, not a downloaded street map. */
@Composable
fun PresetCourseMap(segments: List<RouteSegment>,route: List<GeoCoordinate>,user: GeoCoordinate?,modifier: Modifier=Modifier) {
    Canvas(modifier.background(Color(0xFFF1F4F6))) {
        val points=segments.flatMap{it.geometry}
        if(points.size<2)return@Canvas
        val lon0=points.minOf{it.longitude};val lat0=points.minOf{it.latitude}
        val scaleX=111195.08*cos(Math.toRadians(lat0));val scaleY=111195.08
        val width=(points.maxOf{it.longitude}-lon0)*scaleX
        val height=(points.maxOf{it.latitude}-lat0)*scaleY
        val margin=22f
        val scale=minOf((size.width-margin*2)/width,(size.height-margin*2)/height)
        val left=(size.width-width*scale)/2;val bottom=(size.height+height*scale)/2
        fun point(g: GeoCoordinate)=Offset((left+(g.longitude-lon0)*scaleX*scale).toFloat(),(bottom-(g.latitude-lat0)*scaleY*scale).toFloat())
        segments.forEach{s->s.geometry.zipWithNext().forEach{(a,b)->drawLine(Color(0xFFB5BFC7),point(a),point(b),7f,StrokeCap.Round)}}
        route.zipWithNext().forEach{(a,b)->drawLine(Color(0xFF2963F5),point(a),point(b),7f,StrokeCap.Round)}
        route.lastOrNull()?.let{drawCircle(Color(0xFF147C4B),9f,point(it))}
        user?.let{drawCircle(Color.White,11f,point(it));drawCircle(Color(0xFF15398B),7f,point(it))}
    }
}
