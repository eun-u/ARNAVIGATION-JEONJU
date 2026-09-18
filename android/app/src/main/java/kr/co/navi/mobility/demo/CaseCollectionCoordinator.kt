package kr.co.navi.mobility.demo

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import kr.co.navi.mobility.ai.LatestFramePipeline
import kr.co.navi.mobility.ai.PerceptionEngine
import kr.co.navi.mobility.ai.mediapipe.MediaPipeObjectDetectorEngine
import kr.co.navi.mobility.ar.ArCoreNavigationView
import kr.co.navi.mobility.data.remote.NaviApiClient
import kr.co.navi.mobility.data.remote.UrlConnectionHttpTransport
import kr.co.navi.mobility.guidance.contract.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class CollectionPhase { IDLE, PREPARING, RECORDING, SAVING, DONE, ERROR }
data class CollectionState(
    val phase: CollectionPhase = CollectionPhase.IDLE,
    val instruction: String = "PoC 보행로에서 시작하세요. 약 3분 동안 안내에 따라 촬영하면 자동으로 저장합니다.",
    val step: Int = 0, val remainingSeconds: Int = 180, val frames: Int = 0,
    val savedPath: String? = null, val complete: Boolean = false, val offline: Boolean = false,
) { val busy get() = phase in setOf(CollectionPhase.PREPARING, CollectionPhase.RECORDING, CollectionPhase.SAVING) }

private class CollectionFrame(
    val elapsedMs: Long, val sensors: JSONObject, val original: PerceptionFrameLease,
) : PerceptionFrameLease by original

/** Capture only. Never creates a route, invents calibration, labels ground truth or updates Graph. */
class CaseCollectionCoordinator(context: Context, val connection: ServerConnection) : AutoCloseable {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = MutableStateFlow(CollectionState())
    val runId = "collect-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(8)
    val directory = File(context.getExternalFilesDir(null), "jeonju/collections/$runId")
    private val sensors = CollectionSensors(context)
    private var view: ArCoreNavigationView? = null
    @Volatile private var latestSpatial: SpatialFrameContext? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var active = false
    private var startedAt = 0L
    private var prepJob: Job? = null
    private var timer: Job? = null
    private var pipeline: LatestFramePipeline? = null
    private var engine: PerceptionEngine? = null
    private var writer: ClipWriter? = null
    private var disposed = false
    private var ttsReady = false
    private var tts: TextToSpeech? = null
    private var networkStatus = "not_checked"
    private var recordingFailure: String? = null
    private val counts = IntArray(3)
    private var trackingFrames = 0
    private var depthFrames = 0
    private var semanticsFrames = 0
    private var locationFrames = 0
    private var lightFrames = 0
    private var lastPrompt = ""
    private var lastSpokenAt = 0L

    init {
        tts = TextToSpeech(this.context) { status -> scope.launch {
            yield()
            ttsReady = status == TextToSpeech.SUCCESS && (tts?.setLanguage(Locale.KOREAN) ?: -2) >= TextToSpeech.LANG_AVAILABLE
        } }
    }

    fun attach(ar: ArCoreNavigationView) {
        view = ar
        ar.setPocCalibration(null)
        ar.updatePocRoute(emptyList(), null)
        ar.setPerceptionListener { spatial, frame ->
            latestSpatial = spatial
            lastFrameAt = SystemClock.elapsedRealtime()
            if (active) {
                val stamped = CollectionFrame(lastFrameAt-startedAt, sensors.snapshot(), frame)
                pipeline?.submit(spatial, stamped) ?: frame.close()
            } else frame.close()
        }
    }
    fun detach(ar: ArCoreNavigationView) { ar.setPerceptionListener(null); if(view === ar) view = null }

    fun permissionDenied() { state.update { it.copy(instruction = "촬영하려면 카메라 권한이 필요합니다. 시작을 눌러 허용해주세요.") } }

    fun start() {
        if (state.value.phase != CollectionPhase.IDLE || disposed) return
        state.value = CollectionState(CollectionPhase.PREPARING, "카메라를 앞쪽 보행로로 향해주세요. 촬영을 준비하고 있습니다.")
        prepJob = scope.launch {
            try {
                check((context.getExternalFilesDir(null)?.usableSpace ?: 0L) >= 2L*1024*1024*1024) { "저장 공간을 2GB 이상 확보한 뒤 다시 시작해주세요." }
                sensors.start()
                val expected = JSONObject(context.assets.open("jeonju_scope.json").bufferedReader().use { it.readText() })
                // Connectivity is recorded, but offline collection uses the bundled scope revision.
                networkStatus = try {
                    val server = withTimeout(8_000) { NaviApiClient(connection.url, UrlConnectionHttpTransport(3000,3000), accessToken=connection.token).getJeonjuBootstrap() }
                    val actual = JSONObject(server.toString())
                    if(listOf("region_id","dataset_revision","scope_revision","graph_sha256").all { actual.optString(it)==expected.getString(it) }) "verified" else "revision_mismatch_collection_only"
                } catch (timeout: TimeoutCancellationException) { "unavailable_collection_only" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "unavailable_collection_only" }
                state.update { it.copy(offline=networkStatus!="verified") }
                withContext(Dispatchers.Default) { engine = MediaPipeObjectDetectorEngine(context) }
                withTimeout(30_000) {
                    while(view == null || lastFrameAt == 0L || SystemClock.elapsedRealtime()-lastFrameAt > 2000) delay(100)
                }
                writer = ClipWriter(directory, expected, null, "guided-collection", collectionOnly=true)
                writeState("recording_preparation", null)
                checkNotNull(view).startPocRecording(File(directory, "recording.mp4"))
                pipeline = LatestFramePipeline(scope, checkNotNull(engine), ::consume) { error ->
                    scope.launch { recordingFailure = error.javaClass.simpleName; stop("frame_processing_failed") }
                }
                engine = null // Pipeline owns the model from this point.
                startedAt = SystemClock.elapsedRealtime()
                active = true
                writeState("recording", null)
                state.update { it.copy(phase=CollectionPhase.RECORDING) }
                timer = scope.launch { guide() }
            } catch (error: Exception) {
                if(error is CancellationException && error !is TimeoutCancellationException)throw error
                engine?.close(); engine=null; sensors.stop()
                view?.finishPocRecording()
                if(directory.isDirectory) {
                    recordingFailure=error.javaClass.simpleName
                    writeState("preparation_failed", error.javaClass.simpleName)
                }
                state.update { it.copy(phase=CollectionPhase.ERROR, instruction=if(error is TimeoutCancellationException) "카메라가 준비되지 않았습니다. 앱을 다시 열고 카메라 권한을 확인해주세요." else error.message ?: "촬영을 준비하지 못했습니다. 다시 시도해주세요.") }
            }
        }
    }

    private suspend fun guide() {
        var badTrackingAt = 0L
        var previousStep = -1
        while(active) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now-startedAt
            val step = CaseCollectionPlan.stepAt(elapsed)
            if(step == null) { stop("automatic_completed"); return }
            val index = CaseCollectionPlan.steps.indexOf(step)
            val noFrames = now-lastFrameAt > 2000
            val tracking = !noFrames && latestSpatial?.trackingQuality == TrackingQuality.TRACKING
            if(tracking) badTrackingAt=0 else if(badTrackingAt==0L) badTrackingAt=now
            val instruction = if(!tracking && now-badTrackingAt >= 3000)
                "안전한 곳에 잠시 멈춰 주변 보행로를 천천히 비춰주세요. 촬영은 계속되고 있습니다."
                else step.instruction
            state.update { it.copy(step=index+1, remainingSeconds=CaseCollectionPlan.remainingSeconds(elapsed), instruction=instruction) }
            speak(instruction, force=index!=previousStep)
            previousStep=index
            if(noFrames && now-lastFrameAt > 15_000) { stop("camera_frames_stalled"); return }
            if(directory.usableSpace < 512L*1024*1024) { stop("storage_low"); return }
            delay(500)
        }
    }

    private suspend fun consume(spatial: SpatialFrameContext, frame: PerceptionFrameLease, result: PerceptionResult) {
        val stamped = frame as CollectionFrame
        val step = CaseCollectionPlan.stepAt(stamped.elapsedMs) ?: return
        val detections = JSONArray(result.detections.map { d -> JSONObject().put("label",d.label).put("confidence",d.confidence)
            .put("bounds",JSONArray(listOf(d.bounds.left,d.bounds.top,d.bounds.right,d.bounds.bottom))) })
        val metadata = JSONObject().put("elapsed_ms",stamped.elapsedMs).put("prompt_window",step.id)
            .put("case_label_verified",false).put("sensors",stamped.sensors)
            .put("model_version",result.modelVersion).put("inference_ms",result.inferenceMillis).put("detections",detections)
        checkNotNull(writer).append(spatial,frame,metadata)
        counts[CaseCollectionPlan.steps.indexOf(step)]++
        if(spatial.trackingQuality==TrackingQuality.TRACKING)trackingFrames++
        if(spatial.capture?.depth!=null)depthFrames++
        if(spatial.capture?.semantics!=null)semanticsFrames++
        if(!stamped.sensors.isNull("location"))locationFrames++
        if(!stamped.sensors.isNull("light"))lightFrames++
        state.update { it.copy(frames=it.frames+1) }
    }

    private fun speak(message: String, force: Boolean=false) {
        val now=SystemClock.elapsedRealtime()
        if(ttsReady && (force || message!=lastPrompt && now-lastSpokenAt>=8000)) {
            tts?.speak(message,TextToSpeech.QUEUE_FLUSH,null,"collection-$now")
            lastSpokenAt=now;lastPrompt=message
        }
    }

    fun stop(reason: String="user_stopped") {
        if(state.value.phase == CollectionPhase.PREPARING) {
            prepJob?.cancel()
            sensors.stop()
            // Wait for any in-flight model construction before closing it.
            scope.launch { prepJob?.join(); engine?.close();engine=null; if(disposed)release() }
            state.update { it.copy(phase=CollectionPhase.ERROR,instruction="촬영 준비가 중단됐습니다. 다시 시작해주세요.") }
            return
        }
        if(state.value.phase != CollectionPhase.RECORDING) return
        active=false
        timer?.cancel()
        sensors.stop()
        val recordingResult = view?.finishPocRecording() ?: Result.failure(IllegalStateException("camera_detached"))
        tts?.stop()
        state.update { it.copy(phase=CollectionPhase.SAVING,instruction="촬영이 끝났습니다. 기록을 저장하고 있습니다.") }
        scope.launch {
            try {
                pipeline?.finish(); pipeline=null
                var complete = reason=="automatic_completed" && recordingFailure==null && recordingResult.isSuccess && counts.all { it>0 }
                withContext(Dispatchers.IO) {
                    val summary=JSONObject().put("run_id",runId).put("stop_reason",reason).put("capture_workflow_completed",complete)
                        .put("network_status",networkStatus).put("tts_ready",ttsReady).put("frames",state.value.frames)
                        .put("tracking_frames",trackingFrames).put("depth_frames",depthFrames).put("semantics_frames",semanticsFrames)
                        .put("location_frames",locationFrames).put("light_frames",lightFrames)
                        .put("calibration_status","pending").put("case_labels_verified",false).put("field_acceptance_verified",false)
                        .put("case_windows",JSONArray(CaseCollectionPlan.steps.mapIndexed { index, step -> JSONObject().put("id",step.id)
                            .put("start_ms",step.startMs).put("end_ms",step.endMs).put("frames",counts[index]).put("review_status","pending") }))
                        .put("recording_error",recordingFailure ?: recordingResult.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                    File(directory,"collection_summary.json").writeText(summary.toString(2))
                    writeState(if(complete)"capture_finished" else "interrupted", reason)
                    try { check(recordingResult.isSuccess); checkNotNull(writer).finish() }
                    catch(error: Exception) {
                        complete=false
                        summary.put("capture_workflow_completed",false).put("archive_validation_error",error.javaClass.simpleName)
                        File(directory,"collection_summary.json").writeText(summary.toString(2))
                        writeState("partial_capture",error.javaClass.simpleName)
                    }
                }
                val path=withContext(Dispatchers.IO) { CollectionExport.save(context,directory,runId) }
                state.update { it.copy(phase=CollectionPhase.DONE, complete=complete,savedPath=path,
                    instruction=if(complete)"수집과 저장이 끝났습니다. 이제 휴대폰을 내려도 됩니다." else "수집이 중단되어 여기까지의 기록을 저장했습니다.") }
                speak(state.value.instruction,true)
                Log.i("NaViCollection","saved run=$runId complete=$complete frames=${state.value.frames} path=$path")
            } catch(error: Exception) {
                Log.e("NaViCollection","export_failed run=$runId",error)
                state.update { it.copy(phase=CollectionPhase.ERROR,instruction="기기 안에 원본을 보관했습니다. 압축 저장을 다시 시도해주세요.") }
            } finally { if(disposed)release() }
        }
    }

    fun retryExport() {
        if(state.value.phase!=CollectionPhase.ERROR || !directory.isDirectory)return
        state.update { it.copy(phase=CollectionPhase.SAVING,instruction="보관한 원본을 저장하고 있습니다.") }
        scope.launch {
            try {
                val path=withContext(Dispatchers.IO) { CollectionExport.save(context,directory,runId) }
                state.update { it.copy(phase=CollectionPhase.DONE,savedPath=path,instruction="보관한 기록을 저장했습니다.") }
            } catch (_: Exception) { state.update { it.copy(phase=CollectionPhase.ERROR,instruction="저장 공간을 확인한 뒤 다시 시도해주세요.") } }
            finally { if(disposed)release() }
        }
    }
    private fun writeState(status: String, reason: String?) {
        File(directory,"capture_state.json").writeText(JSONObject().put("run_id",runId).put("status",status)
            .put("reason",reason ?: JSONObject.NULL).put("updated_epoch_ms",System.currentTimeMillis()).toString(2))
    }
    fun onHostPause() { stop("activity_paused") }
    private fun release() { sensors.stop();engine?.close();engine=null;tts?.shutdown();scope.cancel() }
    override fun close() {
        disposed=true
        stop("activity_closed")
        view?.setPerceptionListener(null)
        if(state.value.phase!=CollectionPhase.SAVING && prepJob?.isCompleted!=false)release()
    }
}
