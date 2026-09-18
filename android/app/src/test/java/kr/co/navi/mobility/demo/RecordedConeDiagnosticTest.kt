package kr.co.navi.mobility.demo

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.*
import kr.co.navi.mobility.ai.cone.ConeShapeDetector
import kr.co.navi.mobility.guidance.contract.*
import kr.co.navi.mobility.guidance.fusion.SpatialGuidanceFusion
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real pixels/sensors + explicitly assumed initial frame. No measured map or route claims. */
class RecordedConeDiagnosticTest {
    @Test fun diagnoseRecordedDepthMaskAndPersistence() {
        val raw=System.getenv("NAVI_CONE_EVAL_DIR")
        val intake=System.getenv("NAVI_COLLECTION_DIR")
        assumeTrue(raw!=null && intake!=null)
        val output=File(requireNotNull(raw));val input=File(requireNotNull(intake))
        val buffers=File(output,"frames.tsv").readLines().drop(1).map{it.split('\t')}.associateBy{it[0] to it[1]}
        val detector=ConeShapeDetector();var frames=0
        File(output,"spatial_diagnostic.tsv").bufferedWriter().use { out ->
            out.appendLine("run\tframe_id\telapsed_ms\tcones\tobservations\treason\talignment_provenance")
            input.listFiles()!!.filter{File(it,"frames.jsonl").isFile}.sortedBy{it.name}.forEach { directory ->
                val rows=File(directory,"frames.jsonl").readLines().map{Json.parseToJsonElement(it).jsonObject}
                val first=rows.first{it.string("tracking")=="TRACKING"}
                val startPose=first.pose()
                val loc=first["collection"]!!.jsonObject["sensors"]!!.jsonObject["location"]!!.jsonObject
                val origin=GeoCoordinate(loc.double("lat"),loc.double("lon"))
                // This arbitrary north registration preserves relative distances only.
                val alignment=PocStartAlignment(origin,origin.copy(latitude=origin.latitude+0.0001),startPose,first.long("timestamp_ns"),"poc-start-diagnostic")
                val fusion=SpatialGuidanceFusion()
                for(row in rows) {
                    val id=row.long("frame_id");val ns=row.long("timestamp_ns");val p=row.pose()
                    val source=requireNotNull(buffers[directory.name to id.toString()])
                    val width=source[4].toInt();val height=source[5].toInt()
                    val bytes=ByteBuffer.wrap(File(source[3]).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                    val cones=detector.detect(IntArray(width*height){bytes.int},width,height)
                    val k=row["intrinsics"]!!.jsonArray.map{it.jsonPrimitive.double}
                    val t=row["image_to_texture"]!!.jsonArray.map{it.jsonPrimitive.double}
                    val depth=(row["depth"] as? JsonObject)?.let { d ->
                        val b=ByteBuffer.wrap(File(directory,d.string("path")).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                        DepthSamples(d.int("width"),d.int("height"),IntArray(d.int("width")*d.int("height")){b.short.toInt() and 65535},File(directory,d.string("confidence")).readBytes(),d.long("timestamp_ns"))
                    }
                    val semantics=(row["semantics"] as? JsonObject)?.let { m ->
                        SemanticSamples(m.int("width"),m.int("height"),File(directory,m.string("path")).readBytes(),File(directory,m.string("confidence")).readBytes(),m.long("timestamp_ns"))
                    }
                    val stamp=FrameStamp(id,ns)
                    val s=SpatialFrameContext(stamp,p,alignment.geo(p.position()),PoseAccuracy(relativeTrackingBudgetMeters=alignment.errorAt(p.position(),ns)),TrackingQuality.valueOf(row.string("tracking")),depth!=null,
                        SpatialCapture(CameraIntrinsics(k[0].toInt(),k[1].toInt(),k[2],k[3],k[4],k[5]),ImageTransform(t[0],t[1],t[2],t[3],t[4],t[5]),depth,semantics,alignment,row.int("rotation_degrees"),row.long("observed_at_epoch_ms"),"diagnostic",groundHeightMeters=row["ground_height_m"]?.jsonPrimitive?.doubleOrNull,trackingEpoch=row.long("tracking_epoch")))
                    val observations=fusion.fuse(s,PerceptionResult(stamp,"cone-hsv-shape/1",0,cones))
                    val elapsed=row["collection"]!!.jsonObject.long("elapsed_ms")
                    out.appendLine("${directory.name}\t$id\t$elapsed\t${cones.size}\t${observations.size}\t${fusion.lastReason}\tassumed_first_frame_not_map_validation")
                    frames++
                }
            }
        }
        assertTrue(frames>0)
    }
    private fun JsonObject.string(k:String)=getValue(k).jsonPrimitive.content
    private fun JsonObject.double(k:String)=getValue(k).jsonPrimitive.double
    private fun JsonObject.int(k:String)=getValue(k).jsonPrimitive.int
    private fun JsonObject.long(k:String)=getValue(k).jsonPrimitive.long
    private fun JsonObject.pose(): LocalPose {
        val p=getValue("pose").jsonArray.map{it.jsonPrimitive.float}
        return LocalPose(p[0],p[1],p[2],p[3],p[4],p[5],p[6])
    }
}
