package kr.co.navi.mobility.ai.cone

import kr.co.navi.mobility.guidance.contract.DetectedRegion
import kr.co.navi.mobility.guidance.contract.NormalizedRegion
import kotlin.math.*

/** Image observations only. Orange body + pale band + widening upright silhouette.
 * The score is a rule quality score, not a calibrated ML probability. No route IDs.
 */
class ConeShapeDetector {
    fun detect(argb: IntArray, width: Int, height: Int): List<DetectedRegion> {
        require(width > 0 && height > 0 && argb.size == width * height)
        val scale = min(1.0, 256.0 / max(width, height))
        val w = max(1, (width * scale).toInt()); val h = max(1, (height * scale).toInt())
        val orange = BooleanArray(w*h); val pale = BooleanArray(w*h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = argb[min(height-1, y*height/h)*width+min(width-1, x*width/w)]
            val r=(p ushr 16) and 255; val g=(p ushr 8) and 255; val b=p and 255
            val hi=max(r,max(g,b)); val lo=min(r,min(g,b)); val d=hi-lo
            val hue=if(d==0)0.0 else when(hi) {
                r -> (60.0*(g-b)/d+360)%360
                g -> 60.0*(b-r)/d+120
                else -> 60.0*(r-g)/d+240
            }
            orange[y*w+x]=r>=55 && r>g*1.35 && r>b*1.5 && d>=hi*0.5 && (hue<=38 || hue>=350)
            pale[y*w+x]=hi>=75 && lo>=hi*0.65
        }
        // Bridge narrow horizontal reflective bands, without joining neighbouring cones.
        val connected=orange.copyOf(); val stripePixels=BooleanArray(w*h)
        for(x in 0 until w) {
            var previous=-100
            for(y in 0 until h) if(orange[y*w+x]) {
                if(y-previous in 2..min(20,max(3,h/12)) && (previous+1 until y).any {pale[it*w+x]})
                    for(k in previous+1 until y){connected[k*w+x]=true;stripePixels[k*w+x]=pale[k*w+x]}
                previous=y
            }
        }
        val seen=BooleanArray(w*h);val queue=IntArray(w*h);val result=mutableListOf<DetectedRegion>()
        for(seed in connected.indices) {
            if(!connected[seed] || seen[seed])continue
            var head=0;var tail=1;queue[0]=seed;seen[seed]=true
            var left=w;var right=0;var top=h;var bottom=0;var orangeCount=0
            while(head<tail) {
                val i=queue[head++];val x=i%w;val y=i/w
                left=min(left,x);right=max(right,x);top=min(top,y);bottom=max(bottom,y)
                if(orange[i])orangeCount++
                for(dy in -1..1)for(dx in -1..1) {
                    val xx=x+dx;val yy=y+dy
                    if(xx !in 0 until w || yy !in 0 until h)continue
                    val n=yy*w+xx
                    if(connected[n] && !seen[n]) {seen[n]=true;queue[tail++]=n}
                }
            }
            val bw=right-left+1;val bh=bottom-top+1
            if(bw<6 || bh<16 || orangeCount<20 || bh.toDouble()/bw !in 1.05..5.5)continue
            if(left==0 || right==w-1 || top==0 || bottom==h-1)continue // clipped geometry is ambiguous
            val fill=orangeCount.toDouble()/(bw*bh)
            if(fill !in 0.18..0.88)continue
            var upper=0;var lower=0;var upperRows=0;var lowerRows=0;var bandRows=0
            for(y in top..bottom) {
                val relative=(y-top).toDouble()/bh
                var colour=0;var stripe=0
                for(x in left..right) {
                    if(orange[y*w+x])colour++
                    if(x in left+bw/4..right-bw/4 && stripePixels[y*w+x])stripe++
                }
                if(colour>0 && relative in 0.10..0.40){upper+=colour;upperRows++}
                if(colour>0 && relative in 0.60..0.90){lower+=colour;lowerRows++}
                if(relative in 0.12..0.70 && stripe>=max(1,bw/4))bandRows++
            }
            if(upperRows==0 || lowerRows==0 || upper<1 || bandRows<1)continue
            val widening=(lower.toDouble()/lowerRows/(upper.toDouble()/upperRows)).coerceAtMost(3.0)
            if(widening<1.12)continue
            val score=(0.58+min(0.14,bandRows.toDouble()/bh)+min(0.18,(widening-1)*0.10)).coerceIn(0.0,0.9).toFloat()
            result+=DetectedRegion("traffic_cone",score,NormalizedRegion(left.toFloat()/w,top.toFloat()/h,(right+1f)/w,(bottom+1f)/h))
        }
        return result.sortedByDescending{it.confidence}.take(12)
    }

    companion object { const val REVISION="cone-hsv-shape/1" }
}
