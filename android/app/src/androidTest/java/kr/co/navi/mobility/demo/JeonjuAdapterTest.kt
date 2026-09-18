package kr.co.navi.mobility.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.navi.mobility.ai.FramePixels
import kr.co.navi.mobility.guidance.contract.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/** Synthetic adapter/resource tests only. These are not field captures or AI scene evidence. */
@RunWith(AndroidJUnit4::class)
class JeonjuAdapterTest {
    @Test fun rawCollectionKeepsDeviceLocationSeparateFromARAlignmentAndCannotReplayAsValidatedClip() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"collection-contract-${UUID.randomUUID()}")
        val frame=Frame(0)
        val source=contractSpatial(frame,contractCalibration())
        val spatial=source.copy(geoCoordinate=null,accuracy=null,capture=source.capture!!.copy(calibration=null))
        try {
            val metadata=JSONObject().put("prompt_window","C01").put("case_label_verified",false)
                .put("sensors",JSONObject().put("location",JSONObject().put("lat",35.846).put("lon",127.131).put("accuracy_m",12.0)))
            val writer=ClipWriter(dir,JSONObject(),null,"collection-test",collectionOnly=true)
            writer.append(spatial,frame,metadata)
            val row=JSONObject(File(dir,"frames.jsonl").readText())
            assertTrue(row.isNull("calibration"))
            assertTrue(row.isNull("geo_coordinate"))
            assertTrue(row.isNull("horizontal_accuracy_m"))
            assertEquals(12.0,row.getJSONObject("collection").getJSONObject("sensors").getJSONObject("location").getDouble("accuracy_m"),0.0)
            assertFalse(File(dir,"calibration.json").exists())
            assertThrows(IllegalArgumentException::class.java) { writer.finish() }
            File(dir,"clip_manifest.json").writeText(JSONObject().put("schema_version",3).toString())
            assertReaderRejects("frame_anchor_calibration_required",dir,JSONObject())
        } finally { frame.close();dir.deleteRecursively() }
    }

    private class Frame(override val rotationDegrees: Int): PerceptionFrameLease {
        override val stamp=FrameStamp(1,1_000_000_000)
        override val width=2; override val height=2
        override val pixelFormat=PixelFormat.YUV_420_888
        private fun bytes(vararg bytes: Int)=ByteBuffer.wrap(bytes.map{it.toByte()}.toByteArray()).apply{position(1)}
        override val planes=listOf(
            FramePlane(bytes(7,16,235,88,88,81,145,88,88),4,1),
            FramePlane(bytes(7,128,88),2,2),FramePlane(bytes(7,128,88),2,2))
        var closed=false
        override fun close(){closed=true}
    }

    @Test fun yuvOffsetsPaddedStridesAndAllRotationsMatchPixels() {
        val original=FramePixels.bitmap(Frame(0))
        try {
            assertEquals(0,android.graphics.Color.red(original.getPixel(0,0)))
            assertTrue(android.graphics.Color.red(original.getPixel(1,0))>=254)
            val expected=mapOf(0 to (0 to 0),90 to (1 to 0),180 to (1 to 1),270 to (0 to 1))
            expected.forEach{(rotation,black)->
                val bitmap=FramePixels.bitmap(Frame(rotation))
                try {assertEquals(0,android.graphics.Color.red(bitmap.getPixel(black.first,black.second)))}finally{bitmap.recycle()}
            }
        } finally {original.recycle()}
    }

    @Test fun recordingSidecarsRetainExactDepthConfidenceAndCalibration() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"contract-adapter-${UUID.randomUUID()}")
        val first=CalibrationReference(GeoCoordinate(35.846,127.131),Vec3(0.0,0.0,0.0),.05,"contract A")
        val second=CalibrationReference(GeoCoordinate(35.846+10.0/111195.08,127.131),Vec3(0.0,0.0,-10.0),.05,"contract B")
        val c=MapCalibration.fromReferences(first,second,0,"contract-only")
        val f=Frame(90)
        val depth=DepthSamples(2,2,intArrayOf(0,300,2500,12000),byteArrayOf(0,127,-1,-128),f.stamp.timestampNanos)
        val masks=SemanticSamples(2,2,byteArrayOf(5,5,4,4),byteArrayOf(-1,-1,0,0),f.stamp.timestampNanos)
        val capture=SpatialCapture(CameraIntrinsics(2,2,2.0,2.0,1.0,1.0),ImageTransform(0.0,0.0,.5,0.0,0.0,.5),depth,masks,c,90,1000,"contract_test",0.0)
        val spatial=SpatialFrameContext(f.stamp,LocalPose(0f,1f,0f,0f,0f,0f,1f),first.geo,PoseAccuracy(horizontalMeters=.1),TrackingQuality.TRACKING,true,capture)
        try {
            ClipWriter(dir,JSONObject(),c,"synthetic-adapter-test").append(spatial,f)
            // Do not finish a manifest: there was no ARCore MP4 or real scene capture.
            val row=JSONObject(File(dir,"frames.jsonl").readText())
            assertEquals(90,row.getInt("rotation_degrees"))
            assertEquals(f.stamp.timestampNanos,row.getLong("timestamp_ns"))
            assertArrayEquals(byteArrayOf(0,0,44,1,-60,9,-32,46),File(dir,"depth/1.u16").readBytes())
            assertArrayEquals(depth.confidence,File(dir,"depth/1.conf").readBytes())
            assertArrayEquals(masks.labels,File(dir,"semantics/1.u8").readBytes())
            assertEquals(c,parseCalibration(JSONObject(File(dir,"calibration.json").readText())))
            val saved=android.graphics.BitmapFactory.decodeFile(File(dir,"images/1.png").absolutePath)
            try {assertEquals(0,android.graphics.Color.red(saved.getPixel(0,0)))}finally{saved.recycle()}
            assertFalse(File(dir,"clip_manifest.json").exists())
        } finally {f.close();dir.deleteRecursively()}
        assertTrue(f.closed)
    }

    @Test fun duplicateAppendPoisonsWriterAndNeverPublishesACompleteManifest() {
        withMalformedArchive {dir,_,calibration,row->
            val rejected=File(dir.parentFile,"contract-duplicate-${UUID.randomUUID()}")
            val frame=Frame(0)
            try {
                val writer=ClipWriter(rejected,JSONObject(),calibration,"contract-only")
                val s=contractSpatial(frame,calibration)
                writer.append(s,frame)
                assertThrows(IllegalArgumentException::class.java){writer.append(s,frame)}
                assertThrows(IllegalStateException::class.java){writer.finish()}
                assertFalse(File(rejected,"clip_manifest.json").exists())
            } finally {frame.close();rejected.deleteRecursively()}
        }
    }

    @Test fun unfinishedAndContractOnlyWritersCannotProduceFieldManifests() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"contract-unfinished-${UUID.randomUUID()}")
        val c=contractCalibration();val frame=Frame(0)
        try {
            val writer=ClipWriter(dir,JSONObject(),c,"contract-only")
            assertThrows(IllegalArgumentException::class.java){writer.finish()}
            writer.append(contractSpatial(frame,c),frame)
            assertThrows(IllegalArgumentException::class.java){writer.finish()}
            assertFalse(File(dir,"clip_manifest.json").exists())
            assertFalse(File(dir,"clip_manifest.json.pending").exists())
        } finally {frame.close();dir.deleteRecursively()}
    }

    @Test fun readerRejectsDuplicateFrameIdsAndUnsafeHashPathsBeforeMediaPlayback() {
        withMalformedArchive {dir,bootstrap,_,row->
            val duplicate=JSONObject(row.toString()).put("timestamp_ns",row.getLong("timestamp_ns")+200_000_000)
            writeMalformedManifest(dir,bootstrap,listOf(row,duplicate))
            assertReaderRejects("nonmonotonic_or_duplicate_frame",dir,bootstrap)
            val manifest=writeMalformedManifest(dir,bootstrap,listOf(row))
            manifest.getJSONObject("files").put("../outside.bin","0".repeat(64))
            File(dir,"clip_manifest.json").writeText(manifest.toString())
            assertReaderRejects("unsafe_clip_path",dir,bootstrap)
        }
    }

    @Test fun readerRejectsChangedAnchorMetadataAndTrackingEpochReuse() {
        withMalformedArchive {dir,bootstrap,c,row->
            for(changed in listOf(c.first.copy(label="different reference"),c.first.copy(accuracyMeters=.1))) {
                val frameCalibration=MapCalibration.fromReferences(changed,c.second,c.createdTimestampNanos,c.revision)
                val changedRow=JSONObject(row.toString()).put("calibration",calibrationJson(frameCalibration))
                writeMalformedManifest(dir,bootstrap,listOf(changedRow))
                assertReaderRejects("frame_anchor_reference_mismatch",dir,bootstrap)
            }
            val second=JSONObject(row.toString()).put("frame_id",2).put("timestamp_ns",row.getLong("timestamp_ns")+200_000_000)
                .put("tracking_epoch",row.getLong("tracking_epoch")+1).put("image","images/2.png")
            File(dir,"images/1.png").copyTo(File(dir,"images/2.png"))
            writeMalformedManifest(dir,bootstrap,listOf(row,second))
            assertReaderRejects("calibration_crosses_tracking_epoch",dir,bootstrap)
        }
    }

    @Test fun readerRequiresFinalizedManifestAndARealDecodableMp4() {
        withMalformedArchive {dir,bootstrap,_,row->
            val manifest=writeMalformedManifest(dir,bootstrap,listOf(row)).put("completion_state","interrupted")
            File(dir,"clip_manifest.json").writeText(manifest.toString())
            assertReaderRejects("clip_not_finalized",dir,bootstrap)
            writeMalformedManifest(dir,bootstrap,listOf(row))
            // This intentionally invalid byte file is never a synthetic video or field evidence.
            assertReaderRejects("recording_media_invalid",dir,bootstrap)
        }
    }

    private fun contractCalibration(): MapCalibration {
        val first=CalibrationReference(GeoCoordinate(35.846,127.131),Vec3(0.0,0.0,0.0),.05,"contract A")
        val second=CalibrationReference(GeoCoordinate(35.846+10.0/111195.08,127.131),Vec3(0.0,0.0,-10.0),.05,"contract B")
        return MapCalibration.fromReferences(first,second,0,"contract-only")
    }
    private fun contractSpatial(frame: Frame,c: MapCalibration): SpatialFrameContext {
        val pose=LocalPose(0f,1f,0f,0f,0f,0f,1f)
        val capture=SpatialCapture(CameraIntrinsics(2,2,2.0,2.0,1.0,1.0),ImageTransform(0.0,0.0,.5,0.0,0.0,.5),null,null,c,frame.rotationDegrees,1000,"contract_test",trackingEpoch=1)
        return SpatialFrameContext(frame.stamp,pose,c.geo(pose.position()),PoseAccuracy(c.errorAt(pose.position(),frame.stamp.timestampNanos)),TrackingQuality.TRACKING,false,capture)
    }
    private fun withMalformedArchive(block: (File,JSONObject,MapCalibration,JSONObject)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"invalid-contract-archive-${UUID.randomUUID()}")
        val c=contractCalibration();val frame=Frame(0)
        val bootstrap=JSONObject().put("region_id","contract-region").put("scope_revision","contract-scope").put("dataset_revision","contract-dataset").put("graph_sha256","0".repeat(64))
        try {
            ClipWriter(dir,bootstrap,c,"invalid-contract-only").append(contractSpatial(frame,c),frame)
            File(dir,"recording.mp4").writeText("INVALID CONTRACT INPUT: NOT AN MP4")
            block(dir,bootstrap,c,JSONObject(File(dir,"frames.jsonl").readText()))
        } finally {frame.close();dir.deleteRecursively()}
    }
    private fun writeMalformedManifest(dir: File,bootstrap: JSONObject,rows: List<JSONObject>): JSONObject {
        File(dir,"frames.jsonl").writeText(rows.joinToString("\n",postfix="\n"){it.toString()})
        val files=JSONObject()
        dir.walkTopDown().filter{it.isFile && it.name!="clip_manifest.json"}.forEach{files.put(it.relativeTo(dir).invariantSeparatorsPath,sha256(it))}
        val manifest=JSONObject().put("schema_version",2).put("frame_calibration_policy","per_frame_ARCore_anchor_poses")
            .put("completion_state","finalized").put("recording_duration_ms",1000).put("input_provenance","arcore_live_capture")
            .put("model_sha256","0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb")
            .put("frames",rows.size).put("first_timestamp_ns",rows.first().getLong("timestamp_ns")).put("last_timestamp_ns",rows.last().getLong("timestamp_ns"))
            .put("first_capture_epoch_ms",rows.first().getLong("observed_at_epoch_ms")).put("last_capture_epoch_ms",rows.last().getLong("observed_at_epoch_ms"))
            .put("cpu_image_width",2).put("cpu_image_height",2).put("rotations_degrees",JSONArray(listOf(0))).put("files",files)
        listOf("region_id","scope_revision","dataset_revision","graph_sha256").forEach{manifest.put(it,bootstrap.getString(it))}
        File(dir,"clip_manifest.json").writeText(manifest.toString())
        return manifest
    }
    private fun assertReaderRejects(reason: String,dir: File,bootstrap: JSONObject) {
        val failure=assertThrows(IllegalArgumentException::class.java){ClipReader(dir,bootstrap)}
        assertTrue("expected $reason but got ${failure.message}",failure.message.orEmpty().contains(reason))
    }
}
