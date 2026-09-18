package kr.co.navi.mobility.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import kr.co.navi.mobility.guidance.contract.*
import java.nio.ByteBuffer

object FramePixels {
    fun bitmap(frame: PerceptionFrameLease, rotate: Boolean = true): Bitmap {
        val original=Bitmap.createBitmap(frame.width,frame.height,Bitmap.Config.ARGB_8888)
        try {
        if(frame.pixelFormat==PixelFormat.YUV_420_888) {
            require(frame.planes.size==3)
            val colors=IntArray(frame.width*frame.height)
            fun sample(p: Int,x: Int,y: Int): Int {
                val plane=frame.planes[p]
                return plane.buffer.get(plane.buffer.position()+y*plane.rowStride+x*plane.pixelStride).toInt() and 255
            }
            for(y in 0 until frame.height) for(x in 0 until frame.width) {
                val l=(sample(0,x,y)-16).coerceAtLeast(0)*1.164
                val u=sample(1,x/2,y/2)-128;val v=sample(2,x/2,y/2)-128
                val r=(l+1.596*v).toInt().coerceIn(0,255)
                val g=(l-0.813*v-0.391*u).toInt().coerceIn(0,255)
                val b=(l+2.018*u).toInt().coerceIn(0,255)
                colors[y*frame.width+x]=(255 shl 24) or (r shl 16) or (g shl 8) or b
            }
            original.setPixels(colors,0,frame.width,0,0,frame.width,frame.height)
        } else {
            val plane=frame.planes.single()
            require(plane.pixelStride==4 && plane.rowStride>=frame.width*4)
            val packed=ByteBuffer.allocate(frame.width*frame.height*4)
            val row=ByteArray(frame.width*4);val source=plane.buffer.duplicate();val offset=source.position()
            repeat(frame.height) { y ->source.position(offset+y*plane.rowStride);source.get(row);packed.put(row)}
            packed.flip();original.copyPixelsFromBuffer(packed)
        }
        if(!rotate || frame.rotationDegrees==0) return original
        return Bitmap.createBitmap(original,0,0,original.width,original.height,Matrix().apply{postRotate(frame.rotationDegrees.toFloat())},true).also{original.recycle()}
        } catch(error: Throwable) {original.recycle();throw error}
    }
}
