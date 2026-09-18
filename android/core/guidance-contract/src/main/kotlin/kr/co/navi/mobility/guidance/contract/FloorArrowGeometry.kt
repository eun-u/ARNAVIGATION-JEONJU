package kr.co.navi.mobility.guidance.contract

import kotlin.math.hypot

/** Independent triangles for repeated flat arrows; no line connects adjacent arrows. */
fun floorArrowTriangles(path: List<Vec3>,spacingMeters: Double=2.0): FloatArray {
    require(spacingMeters.isFinite() && spacingMeters>=1.0)
    require(path.all{it.x.isFinite() && it.y.isFinite() && it.z.isFinite()})
    val values=ArrayList<Float>()
    var next=0.8
    var traversed=0.0
    for((a,b) in path.zipWithNext()) {
        val length=hypot(b.x-a.x,b.z-a.z)
        if(length<0.01)continue
        val dx=(b.x-a.x)/length;val dz=(b.z-a.z)/length
        while(next+0.5<=traversed+length) {
            val along=next-traversed
            if(along>=0.5) {
                val cx=a.x+dx*along;val cz=a.z+dz*along
                fun vertex(side: Double,forward: Double) {
                    values.add((cx-dz*side+dx*forward).toFloat())
                    values.add(a.y.toFloat())
                    values.add((cz+dx*side+dz*forward).toFloat())
                }
                vertex(0.0,0.5);vertex(-0.32,0.0);vertex(0.32,0.0)
                vertex(-0.10,0.0);vertex(-0.10,-0.45);vertex(0.10,-0.45)
                vertex(-0.10,0.0);vertex(0.10,-0.45);vertex(0.10,0.0)
            }
            next+=spacingMeters
        }
        traversed+=length
        while(next<traversed+0.5)next+=spacingMeters
    }
    return values.toFloatArray()
}
