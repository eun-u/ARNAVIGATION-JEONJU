package kr.co.navi.mobility.demo

import android.content.Context
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kr.co.navi.mobility.ai.*
import kr.co.navi.mobility.ai.mediapipe.MediaPipeObjectDetectorEngine
import kr.co.navi.mobility.ai.mediapipe.MediaPipeObjectDetectorConfig
import kr.co.navi.mobility.ai.cone.ConeShapeDetector
import kr.co.navi.mobility.ai.offline.AndroidMediaFrameDecoder
import kr.co.navi.mobility.ai.offline.OfflineFrameSource
import kr.co.navi.mobility.ar.ArCoreNavigationView
import kr.co.navi.mobility.ar.PocRenderSample
import kr.co.navi.mobility.data.model.*
import kr.co.navi.mobility.data.remote.*
import kr.co.navi.mobility.guidance.contract.*
import kr.co.navi.mobility.guidance.fusion.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class JeonjuState(
    val message: String="서버·모델·음성을 확인하고 있습니다.", val prepared: Boolean=false,
    val starting: Boolean=false,
    val running: Boolean=false,val terminal: Boolean=false,val ttsReady: Boolean=false,
    val route: RouteResultDto?=null,val snapshot: RouteSnapshot?=null,val guidanceValid: Boolean=false,
    val frames: Int=0,val reroutes: Int=0,val decision: String="대기",val calibration: RouteAlignment?=null,
    val referenceA: Boolean=false,val preview: File?=null,val detections: List<DetectedRegion> = emptyList(),
    val capturingReference: Boolean=false,val referenceMessage: String?=null,val referenceError: Boolean=false,
    val rotation: Int=0,val spatial: SpatialFrameContext?=null,
    val remainingMeters: Double?=null,val arrived: Boolean=false,val progressReason: String="waiting",
    val arGuidanceDeferred: Boolean=false,
    val presetSwitching: Boolean=false,val presetTrigger: String?=null,
)

class JeonjuCoordinator(private val context: Context,val mode: String,val clipId: String,val backend: String,private val autoStart: Boolean=false,requestedRunId: String?=null,accessToken: String="") : AutoCloseable {
    val isHackathon get()=mode=="Hackathon"
    val isPoc get()=mode=="PocLive" || isHackathon
    val isLive get()=mode=="Live" || isPoc
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val api=NaviApiClient(backend,UrlConnectionHttpTransport(5000,8000),accessToken=accessToken)
    private val json=Json{ignoreUnknownKeys=true}
    val state=MutableStateFlow(JeonjuState(message=if(isHackathon)"내장 코스·모델·음성을 확인하고 있습니다." else "서버·모델·음성을 확인하고 있습니다."))
    val runId=(requestedRunId ?: (SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(Date())+"-"+UUID.randomUUID().toString().take(8))).also{require(it.matches(Regex("[A-Za-z0-9_-]{1,80}")))}
    val runDirectory=File(context.getExternalFilesDir(null),"jeonju/runs/$runId").apply{mkdirs()}
    private val events=File(runDirectory,"events.jsonl")
    private var bootstrap: JSONObject?=null
    private var model: MediaPipeObjectDetectorEngine?=null
    private var pipeline: LatestFramePipeline?=null
    private val fusion=SpatialGuidanceFusion()
    private val progressTracker=PocNavigationProgressTracker()
    private var mapper: RouteImpactMapper?=null
    private var scopeSegments: List<RouteSegment> = emptyList()
    val presetMapSegments get()=scopeSegments.toList()
    private var writer: ClipWriter?=null
    private var reader: ClipReader?=null
    private var view: ArCoreNavigationView?=null
    @Volatile private var latestSpatial: SpatialFrameContext?=null
    @Volatile private var active=false
    private var referenceEpoch: Long?=null
    private var referenceCaptureJob: Job?=null
    private var generation=0
    private val rerouteAttempts=PocRerouteAttemptGate()
    private val inferenceTimes=mutableListOf<Long>()
    private val networkTimes=mutableListOf<Long>()
    private val renderIntervals=mutableListOf<Double>()
    private val renderWork=mutableListOf<Double>()
    private val submittedRevisions=mutableSetOf<Int>()
    private var renderSamples=0
    private var lastRenderLogNanos=0L
    private var finalOpenLeases=0
    private var tts: TextToSpeech?=null
    private val ttsInitialized=CompletableDeferred<Boolean>()
    private var job: Job?=null
    private var warmupVerified=false
    private var recordingFile: File?=null
    private var finishStarted=false
    private var userStartCount=0
    private var lastUtteranceId: String?=null
    private var utteranceCompletion: CompletableDeferred<Boolean>?=null
    private var replayStartedNanos=0L
    private var replayFirstTimestampNanos=0L
    private val conePreview=ConeShapeDetector()
    private val previewBusy=AtomicBoolean(false)
    private var previewJob: Job?=null
    private val spokenTurns=mutableSetOf<String>()
    private var lastTurnAt=0L
    private var presetCourse: HackathonCourse?=null
    private val presetConeGate=PresetConeGate()

    init {
        tts=TextToSpeech(context) { status ->
            scope.launch { delay(1);val ready=status==TextToSpeech.SUCCESS && (tts?.setLanguage(Locale.KOREAN) ?: -2)>=TextToSpeech.LANG_AVAILABLE
                state.update{it.copy(ttsReady=ready)};ttsInitialized.complete(ready) }
        }
        tts?.setOnUtteranceProgressListener(object: UtteranceProgressListener() {
            override fun onStart(id: String?) { event("tts_started",JSONObject().put("utterance_id",id).put("current_snapshot",state.value.snapshot?.let{"${it.sessionId}:${it.revision}"})) }
            override fun onDone(id: String?) { event("tts_finished",JSONObject().put("utterance_id",id));if(id==lastUtteranceId)utteranceCompletion?.complete(true) }
            @Deprecated("Platform callback") override fun onError(id: String?) { event("tts_error",JSONObject().put("utterance_id",id));if(id==lastUtteranceId)utteranceCompletion?.complete(false);scope.launch{state.update{it.copy(ttsReady=false,message="음성 출력 실패 · 화면 안내를 확인하세요.")}} }
        })
        scope.launch { prepare() }
    }

    @Synchronized private fun event(type: String,payload: JSONObject=JSONObject()) {
        payload.put("type",type).put("run_id",runId).put("input_mode",mode).put("execution_epoch_ms",System.currentTimeMillis())
        bootstrap?.let{b->listOf("region_id","scope_revision","dataset_revision","graph_sha256").forEach{key->payload.put(key,b.getString(key))}}
        payload.put("model_sha256","0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb").put("model_revision",MediaPipeObjectDetectorConfig.DEFAULT_PIPELINE_REVISION)
            .put("cone_detector_revision",ConeShapeDetector.REVISION).put("cone_score_type","rule_quality_not_calibrated_probability")
        if(isHackathon)payload.put("route_source","user_declared_fixed_course").put("server_used",false)
            .put("autonomous_obstacle_localization",false).put("preset_id",presetCourse?.presetId)
        events.appendText(payload.toString()+"\n")
        Log.i("NaViJeonju",payload.toString())
    }
    private suspend fun prepare() {
        try {
            pocDeadline("preparation",35_000) {
                val bundled=if(isHackathon)context.assets.open("jeonju_hackathon_course.json").bufferedReader().use{it.readText()} else null
                if(bundled!=null)presetCourse=HackathonCourse.parse(bundled)
                val b=if(bundled!=null)JSONObject(bundled) else JSONObject(api.getJeonjuBootstrap().toString())
                val expected=JSONObject(bundled ?: context.assets.open("jeonju_scope.json").bufferedReader().use{it.readText()})
                for(k in listOf("region_id","scope_revision","dataset_revision","graph_sha256")) require(b.getString(k)==expected.getString(k)){"${k}_mismatch"}
                if(isPoc)require(b.optJSONArray("alignment_sources")?.toString()?.contains("poc_start")==true){"고정 코스 시연을 지원하는 서버로 업데이트가 필요합니다."}
                bootstrap=b
                scopeSegments=parseSegments(b.getJSONArray("segments"))
                mapper=RouteImpactMapper(scopeSegments)
                withContext(Dispatchers.Default) {
                    val started=System.nanoTime();val engine=MediaPipeObjectDetectorEngine(context);model=engine
                    // Explicit model loading smoke check. Its synthetic pixels never enter Fusion/routing.
                    val smoke=File(runDirectory,"model-smoke.png")
                    val bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888)
                    try{smoke.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}
                    AndroidMediaFrameDecoder().open(OfflineFrameSource.Image(smoke),FrameStamp(0,0),0).use{frame->engine.analyze(frame)}
                    warmupVerified=true
                    event("model_loaded",JSONObject().put("model_sha256","0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb").put("load_and_warmup_ms",(System.nanoTime()-started)/1_000_000).put("evidence_type","synthetic_model_smoke_only"))
                }
                withTimeoutOrNull(8000){ttsInitialized.await()}
                if(mode=="Replay") reader=withContext(Dispatchers.IO){ClipReader(File(context.getExternalFilesDir(null),"jeonju/replay/$clipId"),b)}
                state.update{it.copy(prepared=true,message=if(mode=="Replay")"재생 입력 검증 완료 · 시작을 누르세요." else if(isPoc)"출발점에서 남쪽 보행로를 향하고 시작하세요." else "두 기준점에서 정합을 준비한 뒤 시작하세요.")}
            }
            if(mode=="SelfTest") {
                val b=requireNotNull(bootstrap)
                val route=api.startJeonjuRoute(RouteRequestDto(b.getJSONObject("origin").dto(),b.getJSONObject("destination").dto(),"demo_jeonju"))
                applyRoute(route,generation)
                state.update{it.copy(message="모델 로딩·서버 경로 확인 완료 · 현장 시연 미검증",terminal=true)}
                writeSummary("model_loading_verified",null)
            } else if(autoStart && mode=="Replay") start()
        } catch(e: CancellationException){throw e}
        catch(e: Exception) {fail("준비 실패: ${e.message}",e)}
    }

    fun attach(ar: ArCoreNavigationView) {
        view=ar
        ar.setPocCalibration(state.value.calibration)
        ar.updatePocRoute(emptyList(),null)
        ar.setPocRenderListener(::recordRender)
        ar.setPerceptionListener { s,frame ->
            latestSpatial=s
            if(active) pipeline?.submit(s,frame) ?: frame.close()
            else {
                previewCones(frame,ar)
                scope.launch {
                    if(s.trackingQuality!=TrackingQuality.TRACKING || (referenceEpoch!=null && referenceEpoch!=s.capture?.trackingEpoch)) {
                        if(state.value.referenceA || state.value.capturingReference || state.value.calibration!=null) {
                            invalidateReferences("AR 추적이 끊겨 기준점이 초기화됐습니다. A부터 다시 기록하세요.")
                        }
                    }
                    state.update{it.copy(spatial=s)}
                }
            }
        }
    }
    fun detach(ar: ArCoreNavigationView) {
        ar.setPerceptionListener(null);ar.setPocRenderListener(null)
        if(view===ar) {
            previewJob?.cancel()
            if(state.value.capturingReference)invalidateReferences("카메라 화면이 닫혔습니다. A부터 다시 기록하세요.")
            view=null
        }
    }
    private fun previewCones(frame: PerceptionFrameLease, owner: ArCoreNavigationView) {
        if(!previewBusy.compareAndSet(false,true)){frame.close();return}
        previewJob=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {
                val detections=withContext(Dispatchers.Default) {
                    val bitmap=FramePixels.bitmap(frame)
                    try {
                        val pixels=IntArray(bitmap.width*bitmap.height)
                        bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                        conePreview.detect(pixels,bitmap.width,bitmap.height)
                    } finally {bitmap.recycle()}
                }
                // Preview inference must not replace the newer tracking/calibration frame.
                if(!active && view===owner)state.update{it.copy(detections=detections,rotation=frame.rotationDegrees)}
            } catch(cancelled: CancellationException){throw cancelled}
            catch(error: Exception){Log.w("NaViJeonju","cone preview unavailable",error)}
            finally {frame.close();previewBusy.set(false)}
        }
    }
    private fun recordRender(sample: PocRenderSample) {
        if(!active)return
        renderSamples++
        sample.frameIntervalMillis?.let{renderIntervals+=it}
        renderWork+=sample.renderWorkMillis
        val firstSubmission=sample.drawSubmitted && submittedRevisions.add(sample.routeRevision)
        if(firstSubmission || sample.timestampNanos-lastRenderLogNanos>=1_000_000_000L) {
            lastRenderLogNanos=sample.timestampNanos
            event(if(firstSubmission)"guidance_render_submitted" else "render_sample",JSONObject()
                .put("route_revision",sample.routeRevision).put("session_id",state.value.snapshot?.sessionId)
                .put("timestamp_ns",sample.timestampNanos).put("frame_interval_ms",sample.frameIntervalMillis ?: JSONObject.NULL)
                .put("render_work_ms",sample.renderWorkMillis).put("draw_submitted",sample.drawSubmitted)
                .put("tracking",sample.tracking).put("evidence_type","actual_gl_submission_not_pixel_or_field_verification"))
        }
    }
    fun captureReference(latitude: String,longitude: String,accuracy: String,label: String,second: Boolean) {
        if(state.value.capturingReference)return
        try {
            check(!active && !state.value.starting && !state.value.terminal){"시연 준비 화면에서 기준점을 기록하세요."}
            val input=parseReferenceInput(latitude,longitude,accuracy,label)
            val ar=requireNotNull(view){"카메라 권한을 허용하고 AR 화면을 열어주세요."}
            val s=requireNotNull(latestSpatial){"카메라로 주변을 천천히 비추며 AR 추적을 기다리세요."}
            require(s.trackingQuality==TrackingQuality.TRACKING){"AR 추적이 유효하지 않습니다. 주변을 천천히 비춘 뒤 다시 기록하세요."}
            val epoch=requireNotNull(s.capture){"카메라 입력을 기다리세요."}.trackingEpoch
            check(!second || state.value.referenceA){"기준점 A를 먼저 기록하세요."}
            val token=generation
            val point=if(second)"B" else "A"
            if(!second)referenceEpoch=null
            state.update{it.copy(capturingReference=true,referenceMessage="기준점 $point 기록 중…",referenceError=false,
                referenceA=if(second)it.referenceA else false,calibration=null)}
            referenceCaptureJob=scope.launch {
                try {
                    val calibration=awaitReferenceCapture<MapCalibration?> { callback ->
                        ar.capturePocReference(input.coordinate,input.accuracyMeters,input.label,second,callback)
                    }
                    if(token!=generation || view!==ar)return@launch
                    val latest=latestSpatial
                    if(latest?.trackingQuality!=TrackingQuality.TRACKING || latest.capture?.trackingEpoch!=epoch) {
                        ar.setPocCalibration(null)
                        invalidateReferences("기록 중 AR 추적이 바뀌었습니다. A부터 다시 기록하세요.")
                        return@launch
                    }
                    referenceEpoch=epoch
                    val feedback=if(calibration==null)"기준점 A 기록 완료 · 5m 이상 떨어진 B로 이동해 B의 좌표를 입력하세요."
                        else "기준점 A·B 정합 완료 · 아래 현장 확인 항목을 체크한 뒤 시연을 시작하세요."
                    state.update{it.copy(referenceA=true,calibration=calibration,message=feedback,referenceMessage=feedback,referenceError=false)}
                    if(calibration!=null)event("calibration_created",calibrationJson(calibration))
                } catch(e: CancellationException){throw e}
                catch(e: Exception) {
                    if(e is ReferenceCaptureTimeout) {
                        // Queued behind any late capture, so its Anchor cannot become active afterward.
                        ar.setPocCalibration(null);referenceEpoch=null
                        state.update{it.copy(referenceA=false,calibration=null)}
                    }
                    referenceFailure(e.message ?: "기준점 기록에 실패했습니다. 다시 시도하세요.")
                } finally {state.update{it.copy(capturingReference=false)}}
            }
        }catch(e: Exception){referenceFailure(e.message ?: "기준점 입력을 확인하세요.")}
    }

    private fun referenceFailure(message: String) {
        state.update{it.copy(message=message,referenceMessage=message,referenceError=true)}
    }

    private fun invalidateReferences(message: String) {
        referenceCaptureJob?.cancel();referenceCaptureJob=null;referenceEpoch=null
        state.update{it.copy(referenceA=false,calibration=null,capturingReference=false,referenceMessage=message,referenceError=true)}
    }

    fun start() {
        if(active || state.value.starting || state.value.capturingReference || !state.value.prepared || state.value.terminal) return
        state.update{it.copy(starting=true,message="현재 위치에서 경로를 계산합니다.")};userStartCount++
        job=scope.launch {
            try {
                check(state.value.ttsReady){"한국어 TTS 음성 데이터가 준비되지 않았습니다."}
                val b=requireNotNull(bootstrap)
                if(isPoc) {
                    val ar=requireNotNull(view){"카메라를 준비하세요."}
                    pocStartReadiness(latestSpatial,requireSemantics=!isHackathon)?.let{throw IllegalStateException(it)}
                    val start=awaitReferenceCapture<PocStartAlignment> { callback ->
                        ar.capturePocStart(b.getJSONObject("origin").geo(),b.getJSONObject("poc_start_forward").geo(),callback)
                    }
                    pocDeadline("start_alignment",5_000) {
                        while(latestSpatial?.capture?.calibration?.revision!=start.revision)delay(50)
                    }
                    referenceEpoch=latestSpatial?.capture?.trackingEpoch
                    state.update{it.copy(calibration=start)}
                    event("poc_start_aligned",calibrationJson(start))
                }
                val current=if(mode=="Replay")requireNotNull(reader).firstUsableSpatial() else requireNotNull(latestSpatial){"카메라 입력이 없습니다."}
                val calibration=requireNotNull(current.capture?.calibration){"두 기준점 정합이 필요합니다."}
                require((current.capture?.depth!=null || (isPoc && current.capture?.groundHeightMeters!=null)) && (isHackathon || current.capture?.semantics!=null)){"거리·바닥·보행 영역 입력을 확인하세요."}
                require(current.trackingQuality==TrackingQuality.TRACKING && (current.navigationBudgetMeters() ?: 99.0)<=1.5){"현재 위치 정합 오차가 큽니다."}
                val token=++generation
                val origin=if(isPoc)b.getJSONObject("origin").geo() else requireNotNull(current.geoCoordinate)
                val route=if(isHackathon)requireNotNull(presetCourse).initial("hackathon-$runId") else pocDeadline("initial_route",12_000){api.startJeonjuRoute(RouteRequestDto(CoordinateDto(origin.latitude,origin.longitude),b.getJSONObject("destination").dto(),"demo_jeonju",alignmentSource=calibration.source))}
                require(latestSpatial?.capture?.calibration?.revision==calibration.revision || !isLive){"시작 중 추적이 끊겼습니다. 출발점에서 다시 준비하세요."}
                applyRoute(route,token)
                if(isLive) {
                    val output=File(context.getExternalFilesDir(null),"jeonju/exports/$runId/$clipId")
                    writer=ClipWriter(output,b,calibration,clipId)
                    recordingFile=File(output,"recording.mp4")
                    requireNotNull(view){"AR 화면을 열어주세요."}.startPocRecording(requireNotNull(recordingFile))
                }
                active=true
                state.update{it.copy(running=true,starting=false,calibration=calibration,message=if(isHackathon)"지정 코스 안내 중 · 장애물 앞에서 멈추고 콘을 비추세요." else "자동 안내 중 · 접근성 미확인 시연 경로")}
                event("started",JSONObject().put("user_start_count",userStartCount).put("start_trigger",if(autoStart)"runner_intent" else "start_button").put("obstacle_user_actions",0).put("session_id",route.sessionId))
                val engine=requireNotNull(model);model=null
                if(isLive) pipeline=LatestFramePipeline(scope,engine,::processFrame){error->scope.launch{fail("프레임 처리 실패",error)}}
                else {
                    withContext(Dispatchers.Default) {
                        engine.use {
                            val r=requireNotNull(reader)
                            val replayStart=System.nanoTime();val firstStamp=r.rows.first().getLong("timestamp_ns")
                            replayStartedNanos=replayStart;replayFirstTimestampNanos=firstStamp
                            for(row in r.rows) {
                                ensureActive();if(!active)break
                                val waitMillis=(row.getLong("timestamp_ns")-firstStamp-(System.nanoTime()-replayStart))/1_000_000
                                if(waitMillis>0)delay(waitMillis)
                                val s=r.spatial(row)
                                r.frame(row).use{frame->processFrame(s,frame,engine.analyze(frame))}
                                // Replay clock comes from each recorded stamp, not wall-clock processing latency.
                            }
                        }
                    }
                    finish(drainSpeech=true)
                }
            } catch(e: CancellationException){throw e}
            catch(e: Exception){fail("시작/실행 실패: ${e.message}",e)}
        }
    }

    private suspend fun processFrame(s: SpatialFrameContext,frame: PerceptionFrameLease,result: PerceptionResult) {
        if(!active)return
        if(isPoc && (s.trackingQuality!=TrackingQuality.TRACKING || s.capture?.calibration !is PocStartAlignment)) {
            withContext(Dispatchers.Main) {
                active=false;generation++;tts?.stop();view?.updatePocRoute(emptyList(),null)
                state.update{it.copy(guidanceValid=false,message="위치 추적이 끊겼습니다. 출발점에서 다시 시작하세요.")}
                event("poc_alignment_lost")
                scope.launch{try{finish(finalMessage="위치 추적 중단 · 기록 저장 완료. 출발점에서 다시 준비하세요.")}
                    catch(error: Exception){fail("위치 추적 중단 · 기록 저장 실패",error)}}
            }
            return
        }
        if(mode=="Replay")latestSpatial=s
        writer?.append(s,frame)
        inferenceTimes+=result.inferenceMillis
        val observations=if(isHackathon)emptyList() else fusion.fuse(s,result)
        val decision=if(isHackathon)if(state.value.presetTrigger!=null)"preset_detour_active" else "preset_waiting_for_cone" else fusion.lastReason
        val before=state.value
        val progress=before.snapshot?.let{progressTracker.update(it,s)}
        val valid=s.trackingQuality==TrackingQuality.TRACKING && s.capture?.calibration!=null && (s.navigationBudgetMeters() ?: 99.0)<=1.5 && progress?.remainingMeters!=null
        val recovered=before.arGuidanceDeferred && valid && rerouteAttempts.cooldownElapsed(System.nanoTime()) &&
            PocReroutePolicy.positionReason(scopeSegments,s,s.capture?.calibration?.revision,if(isLive)System.currentTimeMillis() else null)==null
        val arDeferred=before.arGuidanceDeferred && !recovered
        withContext(Dispatchers.Main) {
            if(!active)return@withContext
            state.update{it.copy(frames=it.frames+1,decision=decision,spatial=s,detections=result.detections,rotation=frame.rotationDegrees,
                remainingMeters=if(arDeferred)null else progress?.remainingMeters,progressReason=if(arDeferred)it.progressReason else progress?.reason ?: "no_route",arrived=progress?.arrived==true && !arDeferred,
                arGuidanceDeferred=arDeferred,message=if(recovered)"위치 정합을 다시 확인했습니다. 안내를 계속합니다." else it.message,
                preview=if(mode=="Replay")File(requireNotNull(reader).directory,"images/${frame.stamp.frameId}.png") else null)}
            view?.updatePocRoute(if(valid && !arDeferred && before.guidanceValid)before.snapshot?.geometry.orEmpty() else emptyList(),if(valid && !arDeferred)s.capture?.calibration else null,before.snapshot?.revision ?: 0)
            if(recovered)event("alignment_recovered",JSONObject().put("frame_id",s.stamp.frameId).put("route_revision",before.snapshot?.revision))
        }
        event("frame",JSONObject().put("frame_id",s.stamp.frameId).put("timestamp_ns",s.stamp.timestampNanos).put("observed_at_epoch_ms",s.capture?.observedAtEpochMillis)
            .put("inference_ms",result.inferenceMillis).put("detections",result.detections.size).put("decision",decision)
            .put("detection_labels",JSONArray(result.detections.map{it.label}))
            .put("detection_regions",JSONArray(result.detections.map{d->JSONObject().put("label",d.label).put("confidence",d.confidence).put("bounds",JSONArray(listOf(d.bounds.left,d.bounds.top,d.bounds.right,d.bounds.bottom)))}))
            .put("tracking_quality",s.trackingQuality.name).put("depth_timestamp_ns",s.capture?.depth?.timestampNanos).put("semantics_timestamp_ns",s.capture?.semantics?.timestampNanos)
            .put("calibration_revision",s.capture?.calibration?.revision).put("route_revision",before.snapshot?.revision)
            .put("alignment_source",s.capture?.calibration?.source).put("absolute_accuracy_m",s.accuracy?.horizontalMeters ?: JSONObject.NULL)
            .put("relative_tracking_budget_m",s.accuracy?.relativeTrackingBudgetMeters ?: JSONObject.NULL)
            .put("remaining_m",if(arDeferred)JSONObject.NULL else progress?.remainingMeters ?: JSONObject.NULL).put("progress_reason",state.value.progressReason).put("arrived",progress?.arrived==true && !arDeferred))
        if(active && !arDeferred && progress?.arrived==true) {
            withContext(Dispatchers.Main) {
                if(active) {
                    active=false
                    view?.updatePocRoute(emptyList(),null)
                    state.update{it.copy(guidanceValid=false,message="목적지 도착을 확인했습니다. 기록을 저장합니다.")}
                    event("arrived",JSONObject().put("frame_id",s.stamp.frameId).put("route_revision",before.snapshot?.revision).put("remaining_m",progress.remainingMeters))
                    lastUtteranceId="${before.snapshot?.sessionId}:${before.snapshot?.revision}:arrival";utteranceCompletion=CompletableDeferred()
                    if(tts?.speak("목적지에 도착했습니다.",TextToSpeech.QUEUE_FLUSH,null,lastUtteranceId)!=TextToSpeech.SUCCESS)utteranceCompletion?.complete(false)
                    // Separate coroutine lets the current frame lease close before joining the worker.
                    scope.launch{try{finish(drainSpeech=true)}catch(e:Exception){fail("도착 기록 저장 실패",e)}}
                }
            }
            return
        }
        if(!active || !valid)return
        if(!arDeferred && before.guidanceValid && before.snapshot!=null && s.geoCoordinate!=null) {
            nextPocTurnCue(before.snapshot.geometry,requireNotNull(s.geoCoordinate))?.let { cue ->
                val key="${before.snapshot.revision}:${cue.vertex}"
                if(System.nanoTime()-lastTurnAt>5_000_000_000L && key !in spokenTurns) withContext(Dispatchers.Main) {
                    if(active && state.value.snapshot?.revision==before.snapshot.revision) {
                        spokenTurns+=key;lastTurnAt=System.nanoTime()
                        state.update{it.copy(message=cue.text)}
                        tts?.speak(cue.text,TextToSpeech.QUEUE_ADD,null,"turn:$key")
                        event("turn_cue",JSONObject().put("route_revision",before.snapshot.revision).put("vertex",cue.vertex).put("text",cue.text))
                    }
                }
            }
        }
        if(isHackathon) {
            val course=requireNotNull(presetCourse)
            val user=s.geoCoordinate
            val eligible=before.snapshot?.revision==1 && !arDeferred && user!=null && course.canSwitch(user) &&
                result.stamp==s.stamp && presetFacingObstacle(s,course.triggerPoint)
            if(presetConeGate.update(s.stamp,eligible,result.detections))switchPreset("cone_cue",s)
            return
        }
        if(isLive && (latestSpatial?.trackingQuality!=TrackingQuality.TRACKING || latestSpatial?.capture?.calibration?.revision!=s.capture?.calibration?.revision))return
        for(observation in observations) {
            val snapshot=state.value.snapshot ?: break
            val user=s.geoCoordinate ?: break
            val impact=mapper?.match(observation,snapshot,user)
            event("observation",JSONObject().put("frame_id",s.stamp.frameId).put("observation_id",observation.observationId).put("edge_id",impact?.edgeId ?: JSONObject.NULL).put("decision",if(impact==null)"ambiguous_or_outside_route" else "temporary_avoidance")
                .put("labels",JSONArray(observation.evidence.labels)).put("persistence_ms",observation.persistenceMillis).put("confidence",observation.perceptionConfidence)
                .put("object_position",observation.geoCoordinate?.let(::geoJson)).put("accuracy_m",observation.horizontalAccuracyMeters ?: JSONObject.NULL)
                .put("alignment_source",observation.alignmentSource).put("relative_tracking_budget_m",observation.relativeTrackingBudgetMeters ?: JSONObject.NULL)
                .put("spatial_method",observation.evidence.spatialMethod))
            if(impact!=null && rerouteAttempts.tryAcquire("${impact.edgeId}:${observation.observationId}",System.nanoTime())) reroute(s,impact,snapshot)
        }
    }

    private suspend fun reroute(s: SpatialFrameContext,impact: EdgeImpact,snapshot: RouteSnapshot) {
        val b=requireNotNull(bootstrap);val o=impact.observation
        val current=if(isLive)requireNotNull(latestSpatial) else s
        val token=generation;val attemptKey="${impact.edgeId}:${o.observationId}"
        if(!active)return
        val localReason=PocReroutePolicy.positionReason(scopeSegments,current,o.calibrationRevision,if(isLive)System.currentTimeMillis() else null)
        if(localReason!=null){deferReroute(localReason,attemptKey,snapshot,token,s);return}
        val user=requireNotNull(current.geoCoordinate)
        val eventId=UUID.randomUUID().toString()
        val p=JSONObject().put("event_id",eventId).put("expected_route_revision",snapshot.revision).put("graph_revision",snapshot.graphRevision).put("reason","stationary corridor obstacle")
        listOf("region_id","dataset_revision","scope_revision","graph_sha256").forEach{p.put(it,b.getString(it))}
        p.put("current_position",geoJson(user).put("accuracy_m",current.accuracy?.horizontalMeters ?: JSONObject.NULL).put("alignment_source",o.alignmentSource)
            .put("relative_tracking_budget_m",current.accuracy?.relativeTrackingBudgetMeters ?: JSONObject.NULL).put("timestamp",iso(if(isLive)requireNotNull(current.capture).observedAtEpochMillis else System.currentTimeMillis())).put("calibration_revision",o.calibrationRevision))
        val evidence=JSONObject().put("object_position",geoJson(requireNotNull(o.geoCoordinate))).put("accuracy_m",o.horizontalAccuracyMeters ?: JSONObject.NULL)
            .put("alignment_source",o.alignmentSource).put("relative_tracking_budget_m",o.relativeTrackingBudgetMeters ?: JSONObject.NULL)
            .put("depth_valid",o.evidence.spatialMethod=="raw_depth").put("semantics_valid",s.capture?.semantics!=null)
            .put("spatial_method",o.evidence.spatialMethod).put("ground_height_m",o.evidence.groundHeightMeters ?: JSONObject.NULL)
            .put("ground_plane_valid",o.evidence.spatialMethod=="ground_plane_ray").put("semantics_gate",o.evidence.semanticsGate)
            .put("confidence",o.perceptionConfidence).put("persistence_ms",o.persistenceMillis).put("stationary",!o.dynamic).put("corridor_occupied",o.corridorOccupied)
            .put("model_revision",o.modelVersion).put("calibration_revision",o.calibrationRevision)
            .put("labels",JSONArray(o.evidence.labels)).put("model_sha256","0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb")
        p.put("avoidance_upserts",JSONArray().put(JSONObject().put("edge_id",impact.edgeId).put("observation_id",o.observationId).put("frame_id",s.stamp.frameId.toString())
            .put("observed_at",iso(requireNotNull(s.capture).observedAtEpochMillis)).put("source",if(isPoc)"poc_live_ai" else if(mode=="Replay")"replay_ai" else "live_ai").put("ttl_seconds",60).put("evidence",evidence)))
        event("reroute_requested",JSONObject(p.toString()).put("frame_id",s.stamp.frameId).put("observation_id",o.observationId))
        val started=System.nanoTime()
        try {
            val response=pocDeadline("reroute",18_000){api.rerouteJeonju(snapshot.sessionId,json.parseToJsonElement(p.toString()).jsonObject)}
            networkTimes+=(System.nanoTime()-started)/1_000_000
            val r=JSONObject(response.toString())
            val received=System.nanoTime()
            val applied=withContext(Dispatchers.Main) {
              if(!RouteResponseGate.accept(active,token,generation,snapshot.sessionId,state.value.snapshot,snapshot.revision,
                    r.getString("session_id"),r.getInt("route_revision"),r.getInt("graph_revision"),snapshot.graphRevision)) {
                event("stale_response_discarded",r);return@withContext false
              }
              if(r.getString("status")=="no_accessible_route") {
                withContext(Dispatchers.Main){tts?.stop();view?.updatePocRoute(emptyList(),null);state.update{it.copy(guidanceValid=false,remainingMeters=null,snapshot=snapshot.copy(revision=r.getInt("route_revision"),geometry=emptyList(),segments=emptyList(),distanceMeters=0.0,reasonCode="no_route_within_poc"),message="범위 내 우회 경로가 없습니다. 안내를 멈춥니다.",reroutes=it.reroutes+1)}}
              } else {
                val route=json.decodeFromString<RouteResultDto>(r.getJSONObject("recalculated_route").toString())
                applyRoute(route,token)
                withContext(Dispatchers.Main){state.update{it.copy(reroutes=it.reroutes+1,message="현재 위치에서 우회 경로를 갱신했습니다.")}}
              }
              true
            }
            if(!applied)return
            event("route_applied",r.put("frame_id",s.stamp.frameId).put("observation_id",o.observationId).put("edge_id",impact.edgeId)
                .put("state_dispatch_ms",(System.nanoTime()-received)/1_000_000)
                .put("reaction_ms",if(isLive)System.currentTimeMillis()-(requireNotNull(s.capture).observedAtEpochMillis-o.persistenceMillis) else (System.nanoTime()-(replayStartedNanos+s.stamp.timestampNanos-o.persistenceMillis*1_000_000-replayFirstTimestampNanos))/1_000_000)
                .put("reaction_time_basis",if(isLive)"first_observed_capture_utc_to_dispatch" else "first_observed_frame_1x_execution_to_dispatch"))
        } catch(e: CancellationException){throw e}
        catch(e: Exception){
            val reason=PocReroutePolicy.deferredReason(e)
            if(reason!=null)deferReroute(reason,attemptKey,snapshot,token,s)
            else withContext(Dispatchers.Main){if(isCurrentRequest(snapshot,token))fail("재탐색 실패: ${e.message}",e)}
        }
    }

    private fun isCurrentRequest(snapshot: RouteSnapshot,token: Int): Boolean = active && token==generation &&
        state.value.snapshot?.let{it.sessionId==snapshot.sessionId && it.revision==snapshot.revision}==true

    private suspend fun deferReroute(reason: String,attemptKey: String,snapshot: RouteSnapshot,token: Int,frame: SpatialFrameContext) = withContext(Dispatchers.Main) {
        if(!isCurrentRequest(snapshot,token))return@withContext
        rerouteAttempts.defer(attemptKey,System.nanoTime())
        view?.updatePocRoute(emptyList(),null,snapshot.revision)
        state.update{it.copy(arGuidanceDeferred=true,remainingMeters=null,progressReason=reason,
            message="위치·관측을 다시 확인하고 있습니다. 기존 2D 경로를 유지합니다.")}
        event("reroute_deferred",JSONObject().put("reason_code",reason).put("frame_id",frame.stamp.frameId)
            .put("route_revision",snapshot.revision).put("session_id",snapshot.sessionId).put("retry_cooldown_ms",1000))
    }

    private suspend fun applyRoute(route: RouteResultDto,token: Int) = withContext(Dispatchers.Main) {
        if(token!=generation)return@withContext
        require(route.alignmentSource==if(isPoc)"poc_start" else "measured_references"){"서버와 앱의 위치 정렬 방식이 다릅니다."}
        val b=requireNotNull(bootstrap)
        require(route.scopeRevision==b.getString("scope_revision") && route.graphSha256==b.getString("graph_sha256") && route.datasetRevision==b.getString("dataset_revision"))
        val prior=state.value.snapshot
        if(prior!=null && (route.sessionId!=prior.sessionId || route.routeRevision<=prior.revision))return@withContext
        val snapshot=RouteSnapshot(route.routeRevision,requireNotNull(route.graphRevision),requireNotNull(route.scopeRevision),requireNotNull(route.datasetRevision),requireNotNull(route.sessionId),
            route.geometry.map{GeoCoordinate(it[1],it[0])},route.segments.map{RouteSegment(it.edgeId,it.physicalSegmentId,it.geometry.map{p->GeoCoordinate(p[1],p[0])})},route.distanceM)
        progressTracker.reset()
        state.update{it.copy(route=route,snapshot=snapshot,guidanceValid=true,remainingMeters=snapshot.distanceMeters,arrived=false,progressReason="route_started",arGuidanceDeferred=false)}
        val calibration=latestSpatial?.capture?.calibration ?: reader?.calibration
        view?.updatePocRoute(snapshot.geometry,calibration,snapshot.revision)
        if(state.value.ttsReady) {
            lastUtteranceId="${snapshot.sessionId}:${snapshot.revision}";utteranceCompletion=CompletableDeferred()
            val lead=if(isHackathon && snapshot.revision>1)"지정 우회 경로로 변경했습니다. 뒤돌아 출발점 쪽으로 이동하세요. " else if(snapshot.revision>1)"우회 경로로 변경했습니다. " else "안내를 시작합니다. 앞쪽 바닥이 보이도록 휴대폰을 들고 천천히 걸어가세요. "
            val result=tts?.speak("${lead}남은 거리 ${snapshot.distanceMeters.toInt()}미터입니다.",TextToSpeech.QUEUE_FLUSH,null,lastUtteranceId)
            if(result!=TextToSpeech.SUCCESS){utteranceCompletion?.complete(false);event("tts_error",JSONObject().put("utterance_id",lastUtteranceId).put("speak_result",result))}
        }
        event("guidance_snapshot_dispatched",JSONObject().put("route_revision",snapshot.revision).put("map_revision",snapshot.revision).put("ar_revision",snapshot.revision).put("tts_revision",snapshot.revision).put("distance_m",snapshot.distanceMeters).put("tts_ready",state.value.ttsReady)
            .put("session_id",snapshot.sessionId).put("graph_revision",snapshot.graphRevision)
            .put("ar_view_attached",view!=null).put("render_or_audio_completion_verified",false))
    }

    fun switchPresetManually() {
        if(!isHackathon)return
        scope.launch {
            val spatial=latestSpatial
            if(spatial==null)state.update{it.copy(message="카메라 위치를 확인한 뒤 다시 누르세요.")}
            else switchPreset("operator_button",spatial)
        }
    }

    private fun presetFacingObstacle(s: SpatialFrameContext,target: GeoCoordinate): Boolean {
        val pose=s.localPose ?: return false
        val alignment=s.capture?.calibration ?: return false
        val front=pose.transform(Vec3(0.0,0.0,-1.0));val goal=alignment.world(target,pose.yMeters.toDouble())
        val fx=front.x-pose.xMeters;val fz=front.z-pose.zMeters
        val dx=goal.x-pose.xMeters;val dz=goal.z-pose.zMeters
        val scale=kotlin.math.hypot(fx,fz)*kotlin.math.hypot(dx,dz)
        return scale>0.01 && (fx*dx+fz*dz)/scale>0.5
    }

    private suspend fun switchPreset(trigger: String,s: SpatialFrameContext)=withContext(Dispatchers.Main) {
        val current=state.value
        if(!active || !isHackathon || current.presetSwitching || current.presetTrigger!=null || current.snapshot?.revision!=1)return@withContext
        val course=requireNotNull(presetCourse);val position=s.geoCoordinate
        val fresh=s.capture?.observedAtEpochMillis?.let{System.currentTimeMillis()-it in -1_000L..1_500L}==true
        if(position==null || !fresh || s.trackingQuality!=TrackingQuality.TRACKING ||
            s.capture?.calibration?.revision!=current.calibration?.revision || (s.navigationBudgetMeters() ?: 99.0)>1.5 || !course.canSwitch(position)) {
            state.update{it.copy(message="지정 장애물 앞에서 위치 추적을 확인한 뒤 전환하세요.")}
            return@withContext
        }
        state.update{it.copy(presetSwitching=true)}
        try {
            val route=course.detour(requireNotNull(current.snapshot).sessionId,position)
            applyRoute(route,generation)
            state.update{it.copy(presetTrigger=trigger,reroutes=1,message="지정 우회로 안내 중 · 뒤돌아 출발점 쪽으로 이동하세요.")}
            event("preset_route_switched",JSONObject().put("trigger",trigger).put("frame_id",s.stamp.frameId)
                .put("current_position",geoJson(position)).put("route_revision",2).put("obstacle_position_measured",false)
                .put("manual_obstacle_actions",if(trigger=="operator_button")1 else 0))
        } catch(error: Exception) {
            state.update{it.copy(message="지정 경로 전환 실패: ${error.message}")}
            event("preset_switch_failed",JSONObject().put("message",error.message))
        } finally {state.update{it.copy(presetSwitching=false)}}
    }

    fun stop() {
        active=false;generation++;tts?.stop();view?.updatePocRoute(emptyList(),null)
        state.update{it.copy(guidanceValid=false)}
        scope.launch{try{if(mode=="Replay")job?.cancelAndJoin();finish()}catch(e: Exception){fail("기록 저장 실패: ${e.message}",e)}}
    }
    fun onHostPause() {
        view?.setPocCalibration(null)
        if(state.value.referenceA || state.value.capturingReference || state.value.calibration!=null) {
            invalidateReferences("화면이 중단돼 기준점이 초기화됐습니다. A부터 다시 기록하세요.")
        }
        if(state.value.starting){job?.cancel();fail("준비 중 화면이 닫혔습니다. 다시 준비하세요.",IllegalStateException("lifecycle_pause"))}
        else if(active)stop()
    }
    private suspend fun finish(drainSpeech: Boolean=false,finalMessage: String?=null) {
        if(finishStarted || state.value.terminal)return
        finishStarted=true
        active=false;pipeline?.finish();finalOpenLeases=pipeline?.openLeases?.get() ?: 0;pipeline=null
        check(finalOpenLeases==0){"frame_lease_leak_after_join"}
        if(drainSpeech && state.value.ttsReady && utteranceCompletion!=null) {
            val completed=withTimeoutOrNull(8_000){utteranceCompletion?.await()}
            if(completed!=true)event("tts_completion_unverified",JSONObject().put("utterance_id",lastUtteranceId))
        }
        withContext(Dispatchers.Main){view?.stopDatasetRecording();tts?.stop();view?.updatePocRoute(emptyList(),null)}
        withContext(Dispatchers.IO){writer?.finish()}
        writer=null
        state.update{it.copy(running=false,terminal=true,message=finalMessage ?: if(it.arrived)"목적지 도착 · 기록 저장 완료" else "기록 저장 완료 · 현장 수용 기준은 결과 로그로 별도 확인합니다.")}
        writeSummary(if(mode=="Replay")"replay_executed_acceptance_pending" else "live_recorded_acceptance_pending",null)
    }
    private fun fail(message: String,error: Throwable) {
        active=false;generation++;tts?.stop();view?.updatePocRoute(emptyList(),null)
        pipeline?.close();model?.close();model=null;view?.stopDatasetRecording()
        state.update{it.copy(message=message,running=false,starting=false,terminal=true,guidanceValid=false)}
        event("failure",JSONObject().put("message",message).put("exception",error.javaClass.simpleName))
        // Join from a separate coroutine, after the current consumer releases its lease.
        scope.launch {pipeline?.finish();writeSummary("input_or_runtime_failure",message)}
    }
    @Synchronized private fun writeSummary(status: String,error: String?) {
        fun p95(values: List<Long>): Long?=values.sorted().let{if(it.isEmpty())null else it[(kotlin.math.ceil(it.size*0.95).toInt()-1).coerceAtLeast(0)]}
        fun renderP95(values: List<Double>): Double?=values.sorted().let{if(it.isEmpty())null else it[(kotlin.math.ceil(it.size*0.95).toInt()-1).coerceAtLeast(0)]}
        val s=state.value
        File(runDirectory,"run_summary.json").writeText(JSONObject().put("status",status).put("run_id",runId).put("input_mode",mode).put("clip_id",clipId).put("alignment_source",s.calibration?.source ?: "unregistered")
            .put("absolute_alignment_verified",false)
            .put("route_source",if(isHackathon)"user_declared_fixed_course" else "server_route")
            .put("preset_trigger",s.presetTrigger ?: JSONObject.NULL).put("server_used",!isHackathon)
            .put("autonomous_obstacle_localization",!isHackathon)
            .put("model_loading_verified",warmupVerified).put("frames",s.frames).put("reroutes",s.reroutes).put("tts_ready",s.ttsReady)
            .put("inference_p95_ms",p95(inferenceTimes) ?: JSONObject.NULL).put("backend_p95_ms",p95(networkTimes) ?: JSONObject.NULL).put("open_frame_leases",pipeline?.openLeases?.get() ?: finalOpenLeases)
            .put("render_samples",renderSamples).put("render_frame_interval_p95_ms",renderP95(renderIntervals) ?: JSONObject.NULL)
            .put("render_work_p95_ms",renderP95(renderWork) ?: JSONObject.NULL).put("gl_submitted_route_revisions",JSONArray(submittedRevisions.toList()))
            .put("arrived",s.arrived).put("remaining_m",s.remainingMeters ?: JSONObject.NULL)
            .put("error",error ?: JSONObject.NULL).put("field_acceptance_verified",false).put("recording",recordingFile?.absolutePath ?: JSONObject.NULL)
            .put("event_log",events.absolutePath).toString(2))
    }
    override fun close() {
        active=false;generation++;pipeline?.close();model?.close();view?.setPerceptionListener(null);view?.setPocRenderListener(null);view?.stopDatasetRecording()
        if(!state.value.terminal)writeSummary("interrupted", "activity_closed_before_finish")
        tts?.shutdown();scope.cancel()
    }
    companion object {
        fun parseSegments(a: JSONArray)=List(a.length()){i->val o=a.getJSONObject(i);val g=o.getJSONArray("geometry");RouteSegment(o.getString("edge_id"),o.getString("physical_segment_id"),List(g.length()){j->val p=g.getJSONArray(j);GeoCoordinate(p.getDouble(1),p.getDouble(0))})}
        fun iso(millis: Long)=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(Date(millis))
        private fun JSONObject.dto()=CoordinateDto(getDouble("lat"),getDouble("lon"))
    }
}
