package kr.co.navi.mobility.ar

import android.media.Image
import com.google.ar.core.*
import kr.co.navi.mobility.guidance.contract.*
import kr.co.navi.mobility.guidance.contract.CameraIntrinsics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** ARCore owns each Image; copies leave this method only after the hardware buffers are closed. */
internal class ArPerceptionCapture {
    var frameId=0L
    fun capture(frame: Frame,rotation: Int,calibration: RouteAlignment?,inputMode: String,trackingEpoch: Long,receiver: (SpatialFrameContext,PerceptionFrameLease)->Unit) {
        frame.acquireCameraImage().use { cpu ->
            val stamp=FrameStamp(++frameId,frame.timestamp)
            val k=frame.camera.imageIntrinsics
            val size=k.imageDimensions;val focal=k.focalLength;val centre=k.principalPoint
            val intrinsics=CameraIntrinsics(size[0],size[1],focal[0].toDouble(),focal[1].toDouble(),centre[0].toDouble(),centre[1].toDouble())
            val uv=FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,floatArrayOf(0f,0f,1f,0f,0f,1f),Coordinates2d.TEXTURE_NORMALIZED,uv)
            val transform=ImageTransform(uv[0].toDouble(),uv[1].toDouble(),(uv[2]-uv[0]).toDouble(),(uv[3]-uv[1]).toDouble(),(uv[4]-uv[0]).toDouble(),(uv[5]-uv[1]).toDouble())
            val viewUv=FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,floatArrayOf(0f,0f,1f,0f,0f,1f),Coordinates2d.VIEW_NORMALIZED,viewUv)
            val viewTransform=ImageTransform(viewUv[0].toDouble(),viewUv[1].toDouble(),(viewUv[2]-viewUv[0]).toDouble(),(viewUv[3]-viewUv[1]).toDouble(),(viewUv[4]-viewUv[0]).toDouble(),(viewUv[5]-viewUv[1]).toDouble())
            val depth=runCatching {
                frame.acquireRawDepthImage16Bits().use { image ->
                    frame.acquireRawDepthConfidenceImage().use { confidence ->
                        require(image.width==confidence.width && image.height==confidence.height)
                        val plane=image.planes[0];val buffer=plane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);val base=buffer.position()
                        val values=IntArray(image.width*image.height) { index ->
                            buffer.getShort(base+(index/image.width)*plane.rowStride+(index%image.width)*plane.pixelStride).toInt() and 65535
                        }
                        DepthSamples(image.width,image.height,values,packedBytes(confidence),image.timestamp)
                    }
                }
            }.getOrNull()
            val semantics=runCatching {
                frame.acquireSemanticImage().use { image ->
                    frame.acquireSemanticConfidenceImage().use { confidence ->
                        require(image.width==confidence.width && image.height==confidence.height)
                        SemanticSamples(image.width,image.height,packedBytes(image),packedBytes(confidence),image.timestamp)
                    }
                }
            }.getOrNull()
            val p=frame.camera.pose
            val local=LocalPose(p.tx(),p.ty(),p.tz(),p.qx(),p.qy(),p.qz(),p.qw())
            val tracking=frame.camera.trackingState==TrackingState.TRACKING
            val c=calibration.takeIf{tracking}
            val viewPixel=FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED,floatArrayOf(0.5f,0.75f),Coordinates2d.VIEW,viewPixel)
            val ground=if(tracking)frame.hitTest(viewPixel[0],viewPixel[1]).firstOrNull {
                val plane=it.trackable as? Plane
                plane!=null && plane.trackingState==TrackingState.TRACKING && plane.type==Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(it.hitPose)
            }?.hitPose?.ty()?.toDouble() else null
            val alignmentError=c?.errorAt(local.position(),frame.timestamp)?.takeIf{it.isFinite()}
            val spatial=SpatialFrameContext(stamp,local,c?.geo(local.position()),alignmentError?.let{if(c is PocStartAlignment)PoseAccuracy(relativeTrackingBudgetMeters=it) else PoseAccuracy(horizontalMeters=it)},
                if(tracking)TrackingQuality.TRACKING else TrackingQuality.DEGRADED,depth!=null,
                SpatialCapture(intrinsics,transform,depth,semantics,c,rotation,System.currentTimeMillis(),inputMode,
                    ground,viewTransform,trackingEpoch))
            val planes=cpu.planes.map { plane ->
                val source=plane.buffer.duplicate()
                val bytes=ByteArray(source.remaining());source.get(bytes)
                FramePlane(ByteBuffer.wrap(bytes).asReadOnlyBuffer(),plane.rowStride,plane.pixelStride)
            }
            val lease=CopiedYuvLease(stamp,cpu.width,cpu.height,rotation,planes)
            try {receiver(spatial,lease)} catch(e: Throwable) {lease.close();throw e}
        }
    }
    private fun packedBytes(image: Image): ByteArray {
        val p=image.planes[0];val b=p.buffer;val start=b.position()
        return ByteArray(image.width*image.height){i->b.get(start+(i/image.width)*p.rowStride+(i%image.width)*p.pixelStride)}
    }
}

private class CopiedYuvLease(
    override val stamp: FrameStamp,override val width: Int,override val height: Int,override val rotationDegrees: Int,
    private var storage: List<FramePlane>,
) : PerceptionFrameLease {
    private val closed=AtomicBoolean(false)
    override val planes: List<FramePlane> get() {check(!closed.get());return storage}
    override val pixelFormat=PixelFormat.YUV_420_888
    override fun close() {if(closed.compareAndSet(false,true))storage=emptyList()}
}
