package kr.co.navi.mobility.demo

import android.graphics.Bitmap
import android.os.Build
import kr.co.navi.mobility.ai.FramePixels
import kr.co.navi.mobility.ai.offline.AndroidMediaFrameDecoder
import kr.co.navi.mobility.ai.offline.OfflineFrameSource
import kr.co.navi.mobility.guidance.contract.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

fun sha256(file: File): String = file.inputStream().use { stream ->
    val hash=MessageDigest.getInstance("SHA-256");val b=ByteArray(65536)
    while(true){val n=stream.read(b);if(n<0)break;hash.update(b,0,n)}
    hash.digest().joinToString(""){"%02x".format(it)}
}
fun geoJson(g: GeoCoordinate)=JSONObject().put("lat",g.latitude).put("lon",g.longitude)
fun JSONObject.geo()=GeoCoordinate(getDouble("lat"),getDouble("lon"))
private fun vecJson(v: Vec3)=JSONArray(listOf(v.x,v.y,v.z))
private fun JSONArray.vec(): Vec3 {require(length()==3);return Vec3(getDouble(0),getDouble(1),getDouble(2))}
private fun refJson(r: CalibrationReference)=JSONObject().put("geo",geoJson(r.geo)).put("world",vecJson(r.world)).put("accuracy_m",r.accuracyMeters).put("label",r.label)
private fun JSONObject.reference()=CalibrationReference(getJSONObject("geo").geo(),getJSONArray("world").vec(),getDouble("accuracy_m"),getString("label"))
private fun measuredCalibrationJson(c: MapCalibration)=JSONObject().put("schema_version",1).put("method","two_measured_references")
    .put("revision",c.revision).put("first",refJson(c.first)).put("second",refJson(c.second)).put("yaw_radians",c.yawRadians)
    .put("created_timestamp_ns",c.createdTimestampNanos).put("yaw_error_radians",c.yawErrorRadians).put("coordinate_system","AR_WORLD_METERS_to_WGS84")
    .put("uncertainty_policy","reference_error + distance*sin(yaw_error) + 0.005*distance + 0.001*seconds; not measured tracking accuracy")
fun calibrationJson(c: RouteAlignment): JSONObject = when(c) {
    is MapCalibration -> measuredCalibrationJson(c)
    is PocStartAlignment -> JSONObject().put("schema_version",2).put("method","operator_fixed_course_start")
        .put("revision",c.revision).put("origin",geoJson(c.origin)).put("forward",geoJson(c.forward))
        .put("anchor_pose",JSONArray(listOf(c.anchorPose.xMeters,c.anchorPose.yMeters,c.anchorPose.zMeters,
            c.anchorPose.quaternionX,c.anchorPose.quaternionY,c.anchorPose.quaternionZ,c.anchorPose.quaternionW)))
        .put("created_timestamp_ns",c.createdTimestampNanos).put("alignment_source",c.source)
        .put("absolute_accuracy_m",JSONObject.NULL).put("field_verified",false)
        .put("coordinate_system","operator_assumed_course_coordinates")
        .put("relative_budget_policy","0.20 + 0.004*distance + 0.0005*seconds; configured tolerance, not measured accuracy")
}
fun parseCalibration(j: JSONObject): MapCalibration {
    require(j.getInt("schema_version")==1 && j.getString("method")=="two_measured_references" && j.getString("coordinate_system")=="AR_WORLD_METERS_to_WGS84")
    val computed=MapCalibration.fromReferences(j.getJSONObject("first").reference(),j.getJSONObject("second").reference(),j.getLong("created_timestamp_ns"),j.getString("revision"))
    require(kotlin.math.abs(computed.yawRadians-j.getDouble("yaw_radians"))<1e-6){"calibration_transform_mismatch"}
    require(kotlin.math.abs(computed.yawErrorRadians-j.getDouble("yaw_error_radians"))<1e-6){"calibration_uncertainty_mismatch"}
    return computed
}

private fun safeClipFile(directory: File,name: String): File {
    require(name.isNotBlank() && '\\' !in name && ':' !in name && '\u0000' !in name &&
        name.split('/').none{it.isBlank() || it=="." || it==".."} && !File(name).isAbsolute){"unsafe_clip_path"}
    val file=File(directory,name).canonicalFile
    require(file.path.startsWith(directory.canonicalPath+File.separator)){"unsafe_clip_path"}
    return file
}
private fun imagePixels(width: Int,height: Int): Int {
    require(width in 1..8192 && height in 1..8192 && width.toLong()*height<=16_777_216){"invalid_image_dimensions"}
    return width*height
}
private fun JSONObject.integer(name: String): Long {
    val value=get(name);require(value is Int || value is Long){"integer_required: $name"};return (value as Number).toLong()
}
private fun recordingDuration(file: File): Long {
    require(file.isFile && file.length()>0){"arcore_recording_missing"}
    val retriever=android.media.MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        require(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes"){"recording_video_track_missing"}
        val duration=requireNotNull(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull())
        require(duration>0){"recording_duration_missing"}
        return duration
    } catch(error: Exception) {
        throw IllegalArgumentException("recording_media_invalid",error)
    } finally {retriever.release()}
}
private fun sameReferenceMetadata(a: CalibrationReference,b: CalibrationReference)=
    a.geo==b.geo && a.accuracyMeters==b.accuracyMeters && a.label==b.label
private fun sameCalibrationReferences(a: RouteAlignment,b: RouteAlignment)=a.revision==b.revision &&
    a.createdTimestampNanos==b.createdTimestampNanos && when {
        a is MapCalibration && b is MapCalibration -> sameReferenceMetadata(a.first,b.first) && sameReferenceMetadata(a.second,b.second)
        a is PocStartAlignment && b is PocStartAlignment -> a.origin==b.origin && a.forward==b.forward
        else -> false
    }

/** Exact sampled CPU PNGs are retained alongside ARCore MP4 to avoid approximate MP4/pose seeking. */
class ClipWriter(val directory: File,private val bootstrap: JSONObject,private val calibration: RouteAlignment?,private val clipId: String,private val collectionOnly: Boolean=false) {
    private var frames=0
    private var firstTimestamp=0L
    private var lastTimestamp=0L
    private var firstEpochMillis=0L
    private var lastEpochMillis=0L
    private var imageWidth=0
    private var imageHeight=0
    private val rotations=mutableSetOf<Int>()
    private var lastFrameId=-1L
    private var writeFailed=false
    private var finished=false
    private var containsNonLiveFrames=false
    private val createdAtNanos=System.nanoTime()
    init {
        require(collectionOnly || calibration!=null){"measured_calibration_required"}
        require(!collectionOnly || calibration==null){"collection_must_not_claim_alignment"}
        check(!directory.exists()){ "clip_already_exists" };check(directory.mkdirs())
        listOf("images","depth","semantics").forEach { File(directory,it).mkdirs() }
        calibration?.let { File(directory,"calibration.json").writeText(calibrationJson(it).toString(2)) }
    }
    @Synchronized fun append(s: SpatialFrameContext,frame: PerceptionFrameLease,collection: JSONObject?=null) {
        check(!finished && !writeFailed){"clip_writer_not_writable"}
        try {
        val c=requireNotNull(s.capture);val p=requireNotNull(s.localPose)
        require(s.stamp==frame.stamp && c.rotationDegrees==frame.rotationDegrees){"frame_capture_mismatch"}
        require(s.stamp.frameId>lastFrameId && (frames==0 || s.stamp.timestampNanos>lastTimestamp)){"nonmonotonic_or_duplicate_frame"}
        require(c.rotationDegrees in listOf(0,90,180,270) && c.observedAtEpochMillis>=0 && c.trackingEpoch>=0){"invalid_frame_metadata"}
        imagePixels(frame.width,frame.height)
        require(c.intrinsics.width==frame.width && c.intrinsics.height==frame.height &&
            (frames==0 || frame.width==imageWidth && frame.height==imageHeight)){"frame_image_dimensions_changed"}
        val frameCalibration=c.calibration
        require(frameCalibration==null || (calibration!=null && sameCalibrationReferences(frameCalibration,calibration))){"frame_anchor_reference_mismatch"}
        require(collectionOnly == (collection!=null)){"capture_purpose_mismatch"}
        containsNonLiveFrames=containsNonLiveFrames || c.inputMode!="Live"
        val id=s.stamp.frameId.toString()
        if(frames==0){firstTimestamp=s.stamp.timestampNanos;firstEpochMillis=c.observedAtEpochMillis;imageWidth=frame.width;imageHeight=frame.height}
        lastTimestamp=s.stamp.timestampNanos
        lastEpochMillis=c.observedAtEpochMillis;rotations+=c.rotationDegrees
        val path="images/$id.png"
        val bitmap=FramePixels.bitmap(frame,rotate=false)
        try {File(directory,path).outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {bitmap.recycle()}
        val k=c.intrinsics;val t=c.imageToTexture
        val row=JSONObject().put("frame_id",s.stamp.frameId).put("timestamp_ns",s.stamp.timestampNanos).put("observed_at_epoch_ms",c.observedAtEpochMillis)
            .put("image",path).put("rotation_degrees",c.rotationDegrees).put("tracking",s.trackingQuality.name)
            .put("pose",JSONArray(listOf(p.xMeters,p.yMeters,p.zMeters,p.quaternionX,p.quaternionY,p.quaternionZ,p.quaternionW)))
            .put("intrinsics",JSONArray(listOf(k.width,k.height,k.fx,k.fy,k.cx,k.cy)))
            .put("image_to_texture",JSONArray(listOf(t.originU,t.originV,t.xU,t.xV,t.yU,t.yV)))
            .put("calibration_revision",c.calibration?.revision ?: JSONObject.NULL)
            .put("calibration",c.calibration?.let(::calibrationJson) ?: JSONObject.NULL)
            .put("ground_height_m",c.groundHeightMeters ?: JSONObject.NULL)
            .put("tracking_epoch",c.trackingEpoch)
            .put("geo_coordinate",s.geoCoordinate?.let(::geoJson) ?: JSONObject.NULL)
            .put("horizontal_accuracy_m",s.accuracy?.horizontalMeters?.takeIf{it.isFinite()} ?: JSONObject.NULL)
            .put("relative_tracking_budget_m",s.accuracy?.relativeTrackingBudgetMeters?.takeIf{it.isFinite()} ?: JSONObject.NULL)
            .put("alignment_source",c.calibration?.source ?: "unregistered")
            .put("pose_coordinates","AR_WORLD_METERS_x_right_y_up_negative_z_forward")
        c.imageToView?.let { v -> row.put("image_to_view",JSONArray(listOf(v.originU,v.originV,v.xU,v.xV,v.yU,v.yV))) }
        collection?.let { row.put("collection",it) }
        c.depth?.let { d ->
            val count=imagePixels(d.width,d.height)
            require(d.millimeters.size==count && d.confidence.size==count && d.millimeters.all{it in 0..65535}){"invalid_depth_data"}
            val bytes=ByteBuffer.allocate(d.millimeters.size*2).order(ByteOrder.LITTLE_ENDIAN)
            d.millimeters.forEach{bytes.putShort(it.toShort())}
            File(directory,"depth/$id.u16").writeBytes(bytes.array());File(directory,"depth/$id.conf").writeBytes(d.confidence)
            row.put("depth",JSONObject().put("path","depth/$id.u16").put("confidence","depth/$id.conf").put("width",d.width).put("height",d.height).put("timestamp_ns",d.timestampNanos).put("unit","mm").put("invalid",0).put("byte_order","little_endian"))
        }
        c.semantics?.let { m ->
            val count=imagePixels(m.width,m.height)
            require(m.labels.size==count && m.confidence.size==count){"invalid_semantic_data"}
            File(directory,"semantics/$id.u8").writeBytes(m.labels);File(directory,"semantics/$id.conf").writeBytes(m.confidence)
            row.put("semantics",JSONObject().put("path","semantics/$id.u8").put("confidence","semantics/$id.conf").put("width",m.width).put("height",m.height).put("timestamp_ns",m.timestampNanos).put("labels","ARCore_SemanticLabel"))
        }
        File(directory,"frames.jsonl").appendText(row.toString()+"\n")
        frames++
        lastFrameId=s.stamp.frameId
        } catch(error: Throwable) {writeFailed=true;throw error}
    }
    @Synchronized fun finish(): File {
        check(!finished && !writeFailed){"clip_writer_not_finalizable"}
        require(frames>0){"recording_has_no_frames"}
        require(!containsNonLiveFrames){"contract_test_is_not_a_live_capture"}
        val duration=recordingDuration(safeClipFile(directory,"recording.mp4"))
        val sampledDuration=(lastTimestamp-firstTimestamp)/1_000_000
        require(duration>=sampledDuration-500 && duration<=(System.nanoTime()-createdAtNanos)/1_000_000+2_000){"recording_duration_mismatch"}
        val hashes=JSONObject()
        directory.walkTopDown().filter{it.isFile && it.name!="clip_manifest.json"}.forEach{hashes.put(it.relativeTo(directory).invariantSeparatorsPath,sha256(it))}
        val m=JSONObject().put("schema_version",if(collectionOnly)3 else if(calibration is PocStartAlignment)4 else 2).put("clip_id",clipId).put("input_provenance","arcore_live_capture")
            .put("route_source",bootstrap.optString("route_source","server_route"))
            .put("preset_id",bootstrap.optString("preset_id").ifBlank{null} ?: JSONObject.NULL)
            .put("server_required",bootstrap.optBoolean("server_required",true))
            .put("completion_state","finalized").put("recording_duration_ms",duration)
            .put("first_capture_epoch_ms",firstEpochMillis).put("last_capture_epoch_ms",lastEpochMillis)
            .put("cpu_image_width",imageWidth).put("cpu_image_height",imageHeight).put("rotations_degrees",JSONArray(rotations.sorted()))
            .put("frame_calibration_policy",if(collectionOnly)"unregistered_ARCore_world" else if(calibration is PocStartAlignment)"operator_fixed_course_start_relative_AR" else "per_frame_ARCore_anchor_poses")
            .put("model_sha256","0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb")
            .put("pipeline_revision",kr.co.navi.mobility.ai.mediapipe.MediaPipeObjectDetectorConfig.DEFAULT_PIPELINE_REVISION)
            .put("cone_detector_revision",kr.co.navi.mobility.ai.cone.ConeShapeDetector.REVISION)
            .put("cone_score_type","rule_quality_not_calibrated_probability")
            .put("device",Build.MODEL).put("android_sdk",Build.VERSION.SDK_INT).put("arcore_sdk","1.56.0")
            .put("frames",frames).put("first_timestamp_ns",firstTimestamp).put("last_timestamp_ns",lastTimestamp)
            .put("timebase","ARCore monotonic nanoseconds; per-frame original UTC also recorded")
            .put("object_inference_on_replay","fresh model inference on exact sampled CPU frames")
            .put("semantics_on_replay","recorded sensor masks; not fresh segmentation")
            .put("field_crossing_check",if(collectionOnly || calibration is PocStartAlignment)"pending" else "operator_confirmed_at_capture").put("accessibility_verified",false).put("files",hashes)
        if(collectionOnly) m.put("capture_purpose","case_collection").put("calibration_status","pending")
            .put("replay_eligible",false).put("field_acceptance_verified",false).put("case_labels_verified",false)
        if(calibration is PocStartAlignment)m.put("alignment_source","poc_start").put("absolute_accuracy_m",JSONObject.NULL)
            .put("replay_eligible",false).put("field_acceptance_verified",false).put("case_labels_verified",false)
        listOf("region_id","scope_revision","dataset_revision","graph_sha256").forEach{m.put(it,bootstrap.getString(it))}
        val target=File(directory,"clip_manifest.json")
        val temporary=File(directory,"clip_manifest.json.pending")
        check(!target.exists() && !temporary.exists()){"clip_manifest_already_exists"}
        temporary.outputStream().use{stream->stream.write(m.toString(2).toByteArray(Charsets.UTF_8));stream.fd.sync()}
        check(temporary.renameTo(target)){"clip_manifest_finalize_failed"}
        finished=true
        return target
    }
}

class ClipReader(val directory: File,private val bootstrap: JSONObject) {
    val manifest=JSONObject(safeClipFile(directory,"clip_manifest.json").readText())
    val calibration: MapCalibration
    val rows: List<JSONObject>
    init {
        require(manifest.getInt("schema_version")==2 && manifest.getString("frame_calibration_policy")=="per_frame_ARCore_anchor_poses"){"frame_anchor_calibration_required"}
        require(manifest.getString("completion_state")=="finalized" && !File(directory,"clip_manifest.json.pending").exists()){"clip_not_finalized"}
        require(manifest.getString("input_provenance")=="arcore_live_capture"){"real_arcore_capture_required"}
        require(manifest.getString("model_sha256")=="0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb"){"clip_model_mismatch"}
        listOf("region_id","scope_revision","dataset_revision","graph_sha256").forEach{require(manifest.getString(it)==bootstrap.getString(it)){"${it}_mismatch"}}
        val hashes=manifest.getJSONObject("files")
        hashes.keys().forEach { name ->
            require(hashes.getString(name).matches(Regex("[0-9a-f]{64}"))){"invalid_clip_hash"}
            val file=safe(name);require(file.isFile){"clip_file_missing: $name"}
            require(sha256(file)==hashes.getString(name)){"clip_hash_mismatch: $name"}
        }
        listOf("recording.mp4","calibration.json","frames.jsonl").forEach{require(hashes.has(it)){"clip_hash_missing: $it"}}
        calibration=parseCalibration(JSONObject(safe("calibration.json").readText()))
        rows=safe("frames.jsonl").readLines().filter{it.isNotBlank()}.map(::JSONObject)
        require(rows.isNotEmpty() && rows.size.toLong()==manifest.integer("frames")){"frame_count_mismatch"}
        require(rows.first().integer("timestamp_ns")==manifest.integer("first_timestamp_ns") && rows.last().integer("timestamp_ns")==manifest.integer("last_timestamp_ns")){"frame_timestamp_bounds_mismatch"}
        require(rows.first().integer("observed_at_epoch_ms")==manifest.integer("first_capture_epoch_ms") && rows.last().integer("observed_at_epoch_ms")==manifest.integer("last_capture_epoch_ms")){"capture_time_bounds_mismatch"}
        imagePixels(manifest.getInt("cpu_image_width"),manifest.getInt("cpu_image_height"))
        val declaredRotations=manifest.getJSONArray("rotations_degrees").let{a->(0 until a.length()).map{a.getInt(it)}.toSet()}
        require(declaredRotations==rows.map{it.getInt("rotation_degrees")}.toSet()){"frame_rotations_mismatch"}
        var last=-1L;var lastId=-1L;var lastEpoch=-1L;var calibrationEpoch: Long?=null
        rows.forEach { row ->
            val ns=row.integer("timestamp_ns");val id=row.integer("frame_id")
            require(ns>last && id>lastId){"nonmonotonic_or_duplicate_frame"};last=ns;lastId=id
            val epoch=row.integer("tracking_epoch");require(epoch>=lastEpoch && epoch>=0){"nonmonotonic_tracking_epoch"};lastEpoch=epoch
            require(row.integer("observed_at_epoch_ms")>=0){"invalid_capture_time"}
            require(row.isNull("calibration_revision") || row.getString("calibration_revision")==calibration.revision){"frame_calibration_mismatch"}
            if(!row.isNull("calibration_revision")) {
                val perFrame=parseCalibration(row.getJSONObject("calibration"))
                require(sameCalibrationReferences(perFrame,calibration) && perFrame.createdTimestampNanos<=ns){"frame_anchor_reference_mismatch"}
                require(calibrationEpoch==null || calibrationEpoch==epoch){"calibration_crosses_tracking_epoch"};calibrationEpoch=epoch
                require(row.getString("tracking")==TrackingQuality.TRACKING.name){"calibration_without_tracking"}
            } else {
                require(row.isNull("calibration")){"calibration_revision_missing"}
            }
            require(row.getString("image")=="images/$id.png"){"frame_image_id_mismatch"}
            validateRowMetadata(row)
            val references=mutableListOf(row.getString("image"))
            for(key in listOf("depth","semantics")) {
                if(!row.has(key)) continue
                val part=row.getJSONObject(key)
                val expected=if(key=="depth")"depth/$id.u16" else "semantics/$id.u8"
                require(part.getString("path")==expected && part.getString("confidence")=="$key/$id.conf"){"spatial_file_id_mismatch"}
                references+=part.getString("path");references+=part.getString("confidence")
                // Preserve degraded frames. Fusion will defer stale/missing spatial inputs.
                require(part.integer("timestamp_ns")>=0){"invalid_spatial_timestamp"}
                val pixels=imagePixels(part.getInt("width"),part.getInt("height"))
                require(safe(part.getString("path")).length()==pixels.toLong()*(if(key=="depth")2 else 1) && safe(part.getString("confidence")).length()==pixels.toLong()){"spatial_file_size_mismatch"}
                if(key=="depth")require(part.getString("unit")=="mm" && part.getString("byte_order")=="little_endian" && part.getInt("invalid")==0){"depth_format_mismatch"}
                else require(part.getString("labels")=="ARCore_SemanticLabel"){"semantic_labels_mismatch"}
            }
            references.forEach{require(hashes.has(it)){"unhashed_frame_reference"}}
        }
        val duration=recordingDuration(safe("recording.mp4"))
        require(duration==manifest.integer("recording_duration_ms") && duration>=(rows.last().getLong("timestamp_ns")-rows.first().getLong("timestamp_ns"))/1_000_000-500){"recording_duration_mismatch"}
    }
    private fun safe(name: String): File=safeClipFile(directory,name)
    private fun validateRowMetadata(row: JSONObject) {
        val pose=row.getJSONArray("pose");val k=row.getJSONArray("intrinsics");val t=row.getJSONArray("image_to_texture")
        require(pose.length()==7 && k.length()==6 && t.length()==6){"invalid_spatial_array_length"}
        require((0 until 7).all{pose.getDouble(it).isFinite()} && (0 until 6).all{k.getDouble(it).isFinite() && t.getDouble(it).isFinite()}){"nonfinite_spatial_metadata"}
        require(k.getDouble(0)==k.getInt(0).toDouble() && k.getDouble(1)==k.getInt(1).toDouble()){"invalid_image_dimensions"}
        require(kotlin.math.abs(t.getDouble(2)*t.getDouble(5)-t.getDouble(3)*t.getDouble(4))>1e-20){"degenerate_image_transform"}
        require(imagePixels(k.getInt(0),k.getInt(1))>0 && k.getDouble(2)>0 && k.getDouble(3)>0){"invalid_intrinsics"}
        require(k.getInt(0)==manifest.getInt("cpu_image_width") && k.getInt(1)==manifest.getInt("cpu_image_height")){"frame_dimensions_mismatch"}
        require(kotlin.math.abs((3..6).sumOf{pose.getDouble(it)*pose.getDouble(it)}-1.0)<.02){"invalid_pose_quaternion"}
        require(row.getInt("rotation_degrees") in listOf(0,90,180,270)){"invalid_rotation"}
        require(row.getString("pose_coordinates")=="AR_WORLD_METERS_x_right_y_up_negative_z_forward"){"pose_coordinates_mismatch"}
        TrackingQuality.valueOf(row.getString("tracking"))
        row.optJSONArray("image_to_view")?.let{v->require(v.length()==6 && (0 until 6).all{v.getDouble(it).isFinite()} && kotlin.math.abs(v.getDouble(2)*v.getDouble(5)-v.getDouble(3)*v.getDouble(4))>1e-20){"invalid_view_transform"}}
        require(row.isNull("ground_height_m") || row.getDouble("ground_height_m").isFinite()){"invalid_ground_height"}
        val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(safe(row.getString("image")).absolutePath,bounds)
        require(bounds.outWidth==k.getInt(0) && bounds.outHeight==k.getInt(1) && bounds.outMimeType=="image/png"){"cpu_image_intrinsics_mismatch"}
        if(row.isNull("calibration_revision")) {
            require(row.isNull("geo_coordinate") && row.isNull("horizontal_accuracy_m")){"unaligned_frame_has_map_position"}
        } else {
            val c=parseCalibration(row.getJSONObject("calibration"))
            val p=Vec3(pose.getDouble(0),pose.getDouble(1),pose.getDouble(2))
            require(geodesicDistanceMeters(c.geo(p),row.getJSONObject("geo_coordinate").geo())<=0.01){"frame_geo_pose_mismatch"}
            val error=c.errorAt(p,row.getLong("timestamp_ns"))
            require(if(error.isFinite())!row.isNull("horizontal_accuracy_m") && kotlin.math.abs(row.getDouble("horizontal_accuracy_m")-error)<=1e-5 else row.isNull("horizontal_accuracy_m")){"frame_accuracy_mismatch"}
        }
    }
    fun spatial(row: JSONObject): SpatialFrameContext {
        val pose=row.getJSONArray("pose");val k=row.getJSONArray("intrinsics");val t=row.getJSONArray("image_to_texture")
        require(pose.length()==7 && k.length()==6 && t.length()==6)
        require((0 until 7).all{pose.getDouble(it).isFinite()} && (0 until 6).all{k.getDouble(it).isFinite() && t.getDouble(it).isFinite()})
        require(k.getInt(0)>0 && k.getInt(1)>0 && k.getDouble(2)>0 && k.getDouble(3)>0)
        require(kotlin.math.abs((3..6).sumOf{pose.getDouble(it)*pose.getDouble(it)}-1.0)<.02){"invalid_pose_quaternion"}
        require(row.getInt("rotation_degrees") in listOf(0,90,180,270))
        val p=LocalPose(pose.getDouble(0).toFloat(),pose.getDouble(1).toFloat(),pose.getDouble(2).toFloat(),pose.getDouble(3).toFloat(),pose.getDouble(4).toFloat(),pose.getDouble(5).toFloat(),pose.getDouble(6).toFloat())
        val stamp=FrameStamp(row.getLong("frame_id"),row.getLong("timestamp_ns"))
        val depth=row.optJSONObject("depth")?.let { d ->
            require(d.getString("unit")=="mm" && d.getString("byte_order")=="little_endian")
            val bytes=ByteBuffer.wrap(safe(d.getString("path")).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val values=IntArray(imagePixels(d.getInt("width"),d.getInt("height"))){bytes.short.toInt() and 65535}
            val dc=safe(d.getString("confidence")).readBytes();require(dc.size==values.size && !bytes.hasRemaining())
            DepthSamples(d.getInt("width"),d.getInt("height"),values,dc,d.getLong("timestamp_ns"))
        }
        val semantics=row.optJSONObject("semantics")?.let { m ->
            val labels=safe(m.getString("path")).readBytes();val mc=safe(m.getString("confidence")).readBytes()
            require(labels.size==imagePixels(m.getInt("width"),m.getInt("height")) && mc.size==labels.size)
            SemanticSamples(m.getInt("width"),m.getInt("height"),labels,mc,m.getLong("timestamp_ns"))
        }
        val activeCalibration=if(row.isNull("calibration_revision"))null else parseCalibration(row.getJSONObject("calibration"))
        val viewTransform=row.optJSONArray("image_to_view")?.let{v->ImageTransform(v.getDouble(0),v.getDouble(1),v.getDouble(2),v.getDouble(3),v.getDouble(4),v.getDouble(5))}
        return SpatialFrameContext(stamp,p,activeCalibration?.geo(p.position()),activeCalibration?.let{PoseAccuracy(horizontalMeters=it.errorAt(p.position(),stamp.timestampNanos).takeIf{error->error.isFinite()})},TrackingQuality.valueOf(row.getString("tracking")),depth!=null,
            SpatialCapture(CameraIntrinsics(k.getInt(0),k.getInt(1),k.getDouble(2),k.getDouble(3),k.getDouble(4),k.getDouble(5)),
                ImageTransform(t.getDouble(0),t.getDouble(1),t.getDouble(2),t.getDouble(3),t.getDouble(4),t.getDouble(5)),
                depth,semantics,activeCalibration,row.getInt("rotation_degrees"),row.getLong("observed_at_epoch_ms"),"Replay",if(row.isNull("ground_height_m"))null else row.getDouble("ground_height_m"),viewTransform,row.optLong("tracking_epoch",0)))
    }
    fun firstUsableSpatial(): SpatialFrameContext = rows.asSequence().map(::spatial).firstOrNull { s ->
        val c=s.capture
        val depth=c?.depth;val semantics=c?.semantics
        s.trackingQuality==TrackingQuality.TRACKING && c?.calibration!=null && depth!=null && semantics!=null &&
            (s.accuracy?.horizontalMeters ?: 99.0)<=1.5 &&
            kotlin.math.abs(depth.timestampNanos-s.stamp.timestampNanos)<=100_000_000 &&
            kotlin.math.abs(semantics.timestampNanos-s.stamp.timestampNanos)<=100_000_000
    } ?: error("no_measured_spatial_frames")
    fun frame(row: JSONObject): PerceptionFrameLease {
        val decoded=AndroidMediaFrameDecoder().open(OfflineFrameSource.Image(safe(row.getString("image"))),FrameStamp(row.getLong("frame_id"),row.getLong("timestamp_ns")),row.getInt("rotation_degrees"))
        try {
            val k=row.getJSONArray("intrinsics")
            require(decoded.width==k.getInt(0) && decoded.height==k.getInt(1)){"decoded_frame_dimensions_mismatch"}
            return decoded
        } catch(error: Throwable) {decoded.close();throw error}
    }
}
