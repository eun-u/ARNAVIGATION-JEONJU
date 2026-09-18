package kr.co.navi.mobility.ar

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Pose
import com.google.ar.core.RecordingConfig
import com.google.ar.core.RecordingStatus
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.TextureNotSetException
import com.google.ar.core.exceptions.UnavailableException
import kr.co.navi.mobility.guidance.contract.GeoCoordinate
import kr.co.navi.mobility.guidance.contract.TrackingQuality
import kr.co.navi.mobility.guidance.contract.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Owns the ARCore camera session. CameraX must never be bound while this view is active.
 */
@SuppressLint("ViewConstructor")
class ArCoreNavigationView(
    context: Context,
    private val activity: Activity,
    onStateChanged: (ArRuntimeState) -> Unit,
) : GLSurfaceView(context) {
    private val diagnostics = ArTrackingDiagnosticsStore.process
    private val renderer = ArCoreRenderer(diagnostics, ::onFrameTelemetry, ::onPocRenderSample)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var stateListener = onStateChanged
    @Volatile private var pocRenderListener: ((PocRenderSample)->Unit)?=null
    private var session: Session? = null
    private var availabilityCheckInFlight = false
    private var resumed = false
    private var disposed = false
    private var routeAligned = false
    private var depthSupported = false
    private var lastState = ArRuntimeState()
    private var lastGuidanceInput: GuidanceInput? = null
    private val metricsRecorder = ArSessionMetricsRecorder()
    private var datasetMode = ArDatasetMode.LIVE
    private var datasetMessage: String? = null
    private var activeDataset: File? = null
    private var latestDataset: File? = null
    private var lastDiagnosticLogAt = 0L
    private var lastDiagnosticKey: String? = null
    private var pendingSessionCreationReason = if (diagnostics.snapshot().sessionGeneration == 0) {
        ArSessionTransitionReason.INITIAL_SESSION
    } else {
        ArSessionTransitionReason.VIEW_RECREATE
    }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
        keepScreenOn = true
        latestDataset = findLatestDataset()
        lastState = lastState
            .copy(latestDatasetName = latestDataset?.name)
            .withDiagnostics(diagnostics.snapshot())
        publish(lastState)
    }

    fun updateLifecycleState(state: ArLifecycleState) {
        disposed = when (state) {
            ArLifecycleState.DISPOSED,
            ArLifecycleState.DESTROYED,
            -> true
            ArLifecycleState.CREATED,
            ArLifecycleState.STARTED,
            ArLifecycleState.RESUMED,
            -> false
            else -> disposed
        }
        val transition = when (state) {
            ArLifecycleState.PAUSED -> ArSessionTransitionReason.LIFECYCLE_PAUSE
            ArLifecycleState.DISPOSED -> ArSessionTransitionReason.VIEW_DISPOSE
            else -> null
        }
        val snapshot = diagnostics.updateLifecycle(state, transition, SystemClock.elapsedRealtime())
        logDiagnosticEvent("lifecycle", snapshot)
        publish(lastState.withDiagnostics(snapshot))
    }

    fun setStateListener(listener: (ArRuntimeState) -> Unit) {
        stateListener = listener
        publish(lastState)
    }

    fun setPerceptionListener(listener: ((SpatialFrameContext, PerceptionFrameLease) -> Unit)?) {
        renderer.perceptionListener=listener
    }

    fun setPocRenderListener(listener: ((PocRenderSample)->Unit)?) {pocRenderListener=listener}

    private fun onPocRenderSample(sample: PocRenderSample) {
        val listener=pocRenderListener ?: return
        post {if(pocRenderListener===listener)listener(sample)}
    }

    /** A saved transform cannot establish a new live AR world alignment. */
    fun setPocCalibration(calibration: MapCalibration?) {
        queueEvent {
            if(calibration==null) renderer.clearPocAlignment()
            // Non-null snapshots are owned by the live Anchors; never replace their current poses.
        }
    }

    fun capturePocReference(
        geo: GeoCoordinate,
        accuracyMeters: Double,
        label: String,
        second: Boolean,
        callback: (Result<MapCalibration?>)->Unit,
    ) {
        if(!resumed || disposed) {
            post {callback(Result.failure(IllegalStateException("AR 카메라가 실행 중이어야 합니다.")))}
            return
        }
        queueEvent {
            val result=runCatching {renderer.capturePocReference(geo,accuracyMeters,label,second)}
            post {callback(result)}
        }
    }

    fun updatePocRoute(route: List<GeoCoordinate>,calibration: MapCalibration?,routeRevision: Int=0) {
        require(routeRevision>=0)
        queueEvent {renderer.setPocRoute(route,calibration,routeRevision)}
    }

    fun startPocRecording(target: File) {
        check(datasetMode!=ArDatasetMode.RECORDING)
        val current=checkNotNull(session){"AR 세션이 준비되지 않았습니다."}
        target.parentFile?.mkdirs()
        check(!target.exists()){ "기존 촬영은 덮어쓰지 않습니다." }
        current.startRecording(RecordingConfig(current).setMp4DatasetUri(Uri.fromFile(target)).setAutoStopOnPause(true))
        metricsRecorder.start(File(target.parentFile,"ar_telemetry.csv"))
        activeDataset=target;latestDataset=target;datasetMode=ArDatasetMode.RECORDING
        publishDatasetState()
    }

    fun updateGuidance(
        route: List<GeoCoordinate>,
        user: GeoCoordinate?,
        locationAccuracyMeters: Float?,
        headingDegrees: Float?,
    ) {
        val input = GuidanceInput(route, user, locationAccuracyMeters, headingDegrees)
        if (input == lastGuidanceInput) return
        lastGuidanceInput = input

        val path = if (user != null && headingDegrees != null) {
            buildForwardGuidancePath(
                route = route,
                user = user,
                locationAccuracyMeters = locationAccuracyMeters,
            )
        } else {
            null
        }
        routeAligned = path != null
        queueEvent { renderer.setGuidance(path, headingDegrees) }
        publish(lastState.copy(routeAligned = routeAligned))
    }

    fun resumeSession() {
        if (resumed || disposed) return
        val transition = diagnostics.beginExpectedTransition(
            ArSessionTransitionReason.LIFECYCLE_RESUME,
            SystemClock.elapsedRealtime(),
        )
        logDiagnosticEvent("session_resume", transition)
        val current = session ?: createSession() ?: return
        try {
            current.resume()
            renderer.session = current
            renderer.resetRenderTiming()
            super.onResume()
            resumed = true
        } catch (error: CameraNotAvailableException) {
            closeSession()
            publishUnavailable("ARCore가 후면 카메라를 열지 못했습니다.")
        }
    }

    fun pauseSession(
        reason: ArSessionTransitionReason = ArSessionTransitionReason.LIFECYCLE_PAUSE,
    ) {
        if (!resumed) return
        val transition = diagnostics.beginExpectedTransition(reason, SystemClock.elapsedRealtime())
        logDiagnosticEvent("session_pause", transition)
        if (datasetMode == ArDatasetMode.RECORDING) stopDatasetRecording()
        super.onPause()
        // onPause waits until rendering stops; detach while no GL frame can read these Anchors.
        renderer.clearPocAlignment()
        session?.pause()
        resumed = false
    }

    fun closeSession(
        reason: ArSessionTransitionReason = ArSessionTransitionReason.VIEW_DISPOSE,
    ) {
        val transition = diagnostics.beginExpectedTransition(reason, SystemClock.elapsedRealtime())
        logDiagnosticEvent("session_close", transition)
        pauseSession(reason)
        renderer.clearPocAlignment()
        metricsRecorder.stop()
        renderer.session = null
        session?.close()
        session = null
    }

    fun startDatasetRecording() {
        val current = session
        if (current == null || !resumed) {
            publishDatasetError("ARCore 세션이 준비된 뒤 녹화를 시작하세요.")
            return
        }
        if (datasetMode == ArDatasetMode.PLAYBACK || datasetMode == ArDatasetMode.PLAYBACK_FINISHED) {
            publishDatasetError("재생 모드에서는 새 녹화를 시작할 수 없습니다.")
            return
        }
        if (datasetMode == ArDatasetMode.RECORDING) return

        val directory = datasetDirectory().apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val mp4 = directory.resolve("navi_ar_$stamp.mp4")
        val metrics = directory.resolve("navi_ar_$stamp.csv")
        runCatching {
            val recordingConfig = RecordingConfig(current)
                .setMp4DatasetUri(Uri.fromFile(mp4))
                .setAutoStopOnPause(true)
                .setRecordingRotation(displayRotationDegrees())
            current.startRecording(recordingConfig)
            metricsRecorder.start(metrics)
        }.onSuccess {
            activeDataset = mp4
            latestDataset = mp4
            datasetMode = ArDatasetMode.RECORDING
            datasetMessage = "로컬 AR dataset 녹화 중"
            publishDatasetState()
        }.onFailure { error ->
            runCatching { current.stopRecording() }
            metricsRecorder.stop()
            activeDataset = null
            publishDatasetError("녹화를 시작하지 못했습니다: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun stopDatasetRecording() {
        finishPocRecording()
    }

    /** Callers that publish an archive must distinguish a successful flush from a partial recording. */
    fun finishPocRecording(): Result<Unit> {
        if (datasetMode != ArDatasetMode.RECORDING) return Result.success(Unit)
        val recorded = activeDataset
        val result = runCatching {
            checkNotNull(session).stopRecording()
            check(recorded?.isFile == true && recorded.length() > 0L) { "빈 dataset" }
        }
        metricsRecorder.stop()
        activeDataset = null
        if (result.isSuccess && recorded?.isFile == true && recorded.length() > 0L) {
            latestDataset = recorded
            datasetMode = ArDatasetMode.LIVE
            datasetMessage = "${recorded.name} · ${recorded.length() / 1024L} KB 저장"
            publishDatasetState()
        } else {
            publishDatasetError(
                "녹화 파일 저장에 실패했습니다: " +
                    (result.exceptionOrNull()?.message ?: "빈 dataset"),
            )
        }
        return result
    }

    fun playLatestDataset() {
        if (datasetMode == ArDatasetMode.RECORDING) stopDatasetRecording()
        val target = latestDataset?.takeIf(File::isFile) ?: findLatestDataset()
        if (target == null) {
            publishDatasetError("재생할 AR dataset이 없습니다.")
            return
        }
        val current = session ?: createSession()
        if (current == null) {
            publishDatasetError("ARCore 세션을 만들 수 없습니다.")
            return
        }

        runCatching {
            val transition = diagnostics.beginExpectedTransition(
                ArSessionTransitionReason.PLAYBACK_START,
                SystemClock.elapsedRealtime(),
            )
            logDiagnosticEvent("playback_start", transition)
            if (resumed) {
                super.onPause()
                current.pause()
                resumed = false
            }
            current.setPlaybackDatasetUri(Uri.fromFile(target))
            current.resume()
            renderer.session = current
            renderer.invalidateCameraTexture()
            renderer.resetRenderTiming()
            super.onResume()
            resumed = true
        }.onSuccess {
            latestDataset = target
            datasetMode = ArDatasetMode.PLAYBACK
            datasetMessage = "${target.name} 반복 재생 중"
            publishDatasetState()
        }.onFailure { error ->
            publishDatasetError("dataset 재생을 시작하지 못했습니다: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun returnToLiveSession() {
        if (datasetMode == ArDatasetMode.LIVE) return
        pendingSessionCreationReason = ArSessionTransitionReason.PLAYBACK_TO_LIVE
        closeSession(ArSessionTransitionReason.PLAYBACK_TO_LIVE)
        datasetMode = ArDatasetMode.LIVE
        datasetMessage = "실시간 카메라로 전환됨"
        publishDatasetState()
        resumeSession()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        renderer.displayRotation = display?.rotation ?: Surface.ROTATION_0
    }

    private fun createSession(): Session? {
        publish(lastState.copy(mode = ArRuntimeMode.CHECKING, message = "ARCore 지원 상태 확인 중"))
        return try {
            val arCoreApk = ArCoreApk.getInstance()
            val availability = arCoreApk.checkAvailability(activity)
            when (availability.toStartupDecision()) {
                ArCoreStartupDecision.START_SESSION -> createConfiguredSession()
                ArCoreStartupDecision.WAIT_FOR_RESULT -> {
                    resolveAvailabilityAsync(arCoreApk)
                    null
                }
                ArCoreStartupDecision.USE_2D_INSTALL_REQUIRED -> {
                    publishInstallRequired()
                    null
                }
                ArCoreStartupDecision.USE_2D_UNAVAILABLE -> {
                    publishUnavailable(availability.toUnavailableMessage())
                    null
                }
            }
        } catch (error: UnavailableException) {
            publishUnavailable(error.toUserMessage())
            null
        }
    }

    private fun resolveAvailabilityAsync(arCoreApk: ArCoreApk) {
        if (availabilityCheckInFlight) return
        availabilityCheckInFlight = true
        runCatching {
            arCoreApk.checkAvailabilityAsync(activity) { availability ->
                post {
                    availabilityCheckInFlight = false
                    if (disposed) return@post
                    when (availability.toStartupDecision()) {
                        ArCoreStartupDecision.START_SESSION -> resumeSession()
                        ArCoreStartupDecision.USE_2D_INSTALL_REQUIRED -> publishInstallRequired()
                        ArCoreStartupDecision.USE_2D_UNAVAILABLE -> {
                            publishUnavailable(availability.toUnavailableMessage())
                        }
                        ArCoreStartupDecision.WAIT_FOR_RESULT -> {
                            publishUnavailable("ARCore 지원 여부를 확인할 수 없어 2D 안내로 전환합니다.")
                        }
                    }
                }
            }
        }.onFailure {
            availabilityCheckInFlight = false
            publishUnavailable("ARCore 지원 여부를 확인할 수 없어 2D 안내로 전환합니다.")
        }
    }

    private fun publishInstallRequired() {
        publish(
            lastState.copy(
                mode = ArRuntimeMode.INSTALL_REQUIRED,
                trackingQuality = TrackingQuality.WAITING,
                message = "Google Play Services for AR 설치 또는 업데이트가 필요해 2D 안내로 전환합니다.",
            ),
        )
    }

    private fun createConfiguredSession(): Session {
        val created = Session(activity)
        renderer.beginPocSession()
        val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        renderer.cameraSensorOrientation = requireNotNull(cameraManager.getCameraCharacteristics(created.cameraConfig.cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)) { "Camera sensor orientation unavailable" }
        val config = created.config.apply {
            focusMode = Config.FocusMode.AUTO
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        }
        depthSupported = created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        config.depthMode = if (depthSupported) {
            Config.DepthMode.AUTOMATIC
        } else {
            Config.DepthMode.DISABLED
        }
        if (created.isSemanticModeSupported(Config.SemanticMode.ENABLED)) {
            config.semanticMode=Config.SemanticMode.ENABLED
        }
        created.configure(config)
        session = created
        val transition = diagnostics.onSessionCreated(
            pendingSessionCreationReason,
            SystemClock.elapsedRealtime(),
        )
        pendingSessionCreationReason = ArSessionTransitionReason.VIEW_RECREATE
        logDiagnosticEvent("session_created", transition)
        publish(
            lastState.copy(
                mode = ArRuntimeMode.DEGRADED,
                trackingQuality = TrackingQuality.WAITING,
                depthSupported = depthSupported,
                routeAligned = routeAligned,
                message = "ARCore가 주변 공간을 인식하는 중",
            ).withDiagnostics(transition),
        )
        return created
    }

    private fun ArCoreApk.Availability.toUnavailableMessage(): String = when (this) {
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
            "이 기기에서는 ARCore를 사용할 수 없어 2D 안내로 전환합니다."
        }
        else -> "ARCore 지원 여부를 확인할 수 없어 2D 안내로 전환합니다."
    }

    private fun onFrameTelemetry(telemetry: FrameTelemetry) {
        post {
            when {
                telemetry.playbackStatus == PlaybackStatus.FINISHED -> {
                    datasetMode = ArDatasetMode.PLAYBACK_FINISHED
                    datasetMessage = "dataset 재생 완료"
                }
                telemetry.playbackStatus == PlaybackStatus.IO_ERROR -> {
                    datasetMode = ArDatasetMode.ERROR
                    datasetMessage = "dataset 재생 중 I/O 오류"
                }
                telemetry.recordingStatus == RecordingStatus.IO_ERROR -> {
                    metricsRecorder.stop()
                    datasetMode = ArDatasetMode.ERROR
                    datasetMessage = "dataset 녹화 중 I/O 오류"
                }
            }
            if (datasetMode == ArDatasetMode.RECORDING) {
                val diagnosticsSnapshot = telemetry.diagnostics
                metricsRecorder.append(
                    ArTelemetrySample(
                        elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                        trackingQuality = telemetry.trackingQuality.name,
                        depthActive = telemetry.depthActive,
                        routeAligned = routeAligned,
                        trackingLossCount = diagnosticsSnapshot.totalTrackingLossCount,
                        unexpectedTrackingLossCount = diagnosticsSnapshot.unexpectedTrackingLossCount,
                        expectedTransitionLossCount = diagnosticsSnapshot.expectedTransitionLossCount,
                        lastRecoveryMillis = diagnosticsSnapshot.lastRecoveryMillis,
                        frameTimeMillis = telemetry.frameTimeMillis,
                        message = telemetry.message,
                        trackingFailureReason = diagnosticsSnapshot.trackingFailureReason,
                        lifecycleState = diagnosticsSnapshot.lifecycleState.name,
                        displayInteractive = powerManager.isInteractive,
                        sessionGeneration = diagnosticsSnapshot.sessionGeneration,
                        datasetMode = datasetMode.name,
                        transitionReason = diagnosticsSnapshot.transitionReason.name,
                        expectedSessionTransition = diagnosticsSnapshot.expectedSessionTransition,
                    ),
                )
            }
            val now = SystemClock.elapsedRealtime()
            val diagnosticsSnapshot = telemetry.diagnostics
            val diagnosticKey = listOf(
                telemetry.trackingQuality.name,
                diagnosticsSnapshot.trackingFailureReason,
                diagnosticsSnapshot.lifecycleState.name,
                diagnosticsSnapshot.sessionGeneration,
                datasetMode.name,
                diagnosticsSnapshot.transitionReason.name,
                diagnosticsSnapshot.expectedSessionTransition,
                diagnosticsSnapshot.totalTrackingLossCount,
            ).joinToString("|")
            if (now - lastDiagnosticLogAt >= DIAGNOSTIC_LOG_INTERVAL_MILLIS || diagnosticKey != lastDiagnosticKey) {
                lastDiagnosticLogAt = now
                lastDiagnosticKey = diagnosticKey
                Log.i(
                    DIAGNOSTIC_LOG_TAG,
                    "tracking=${telemetry.trackingQuality.name} " +
                        "depth=${telemetry.depthActive} route_aligned=$routeAligned " +
                        "failure=${diagnosticsSnapshot.trackingFailureReason ?: "NONE"} " +
                        "losses=${diagnosticsSnapshot.totalTrackingLossCount} " +
                        "unexpected_losses=${diagnosticsSnapshot.unexpectedTrackingLossCount} " +
                        "expected_losses=${diagnosticsSnapshot.expectedTransitionLossCount} " +
                        "recovery_ms=${diagnosticsSnapshot.lastRecoveryMillis ?: -1L} " +
                        "frame_ms=${String.format(Locale.US, "%.3f", telemetry.frameTimeMillis)} " +
                        "dataset=${datasetMode.name} " +
                        "lifecycle=${diagnosticsSnapshot.lifecycleState.name} " +
                        "interactive=${powerManager.isInteractive} " +
                        "generation=${diagnosticsSnapshot.sessionGeneration} " +
                        "transition=${diagnosticsSnapshot.transitionReason.name} " +
                        "expected_transition=${diagnosticsSnapshot.expectedSessionTransition}",
                )
            }
            val mode = when (telemetry.trackingQuality) {
                TrackingQuality.TRACKING -> ArRuntimeMode.TRACKING
                TrackingQuality.UNAVAILABLE -> ArRuntimeMode.UNAVAILABLE
                TrackingQuality.WAITING,
                TrackingQuality.DEGRADED,
                -> ArRuntimeMode.DEGRADED
            }
            publish(
                ArRuntimeState(
                    mode = mode,
                    trackingQuality = telemetry.trackingQuality,
                    depthSupported = depthSupported,
                    depthActive = telemetry.depthActive,
                    routeAligned = routeAligned,
                    trackingLossCount = diagnosticsSnapshot.totalTrackingLossCount,
                    unexpectedTrackingLossCount = diagnosticsSnapshot.unexpectedTrackingLossCount,
                    expectedTransitionLossCount = diagnosticsSnapshot.expectedTransitionLossCount,
                    lastRecoveryMillis = diagnosticsSnapshot.lastRecoveryMillis,
                    trackingFailureReason = diagnosticsSnapshot.trackingFailureReason,
                    lifecycleState = diagnosticsSnapshot.lifecycleState,
                    displayInteractive = powerManager.isInteractive,
                    sessionGeneration = diagnosticsSnapshot.sessionGeneration,
                    transitionReason = diagnosticsSnapshot.transitionReason,
                    expectedSessionTransition = diagnosticsSnapshot.expectedSessionTransition,
                    frameTimeMillis = telemetry.frameTimeMillis,
                    message = telemetry.message,
                    datasetMode = datasetMode,
                    latestDatasetName = latestDataset?.name,
                    datasetMessage = datasetMessage,
                ),
            )
        }
    }

    private fun publishUnavailable(message: String) {
        publish(
            lastState.copy(
                mode = ArRuntimeMode.UNAVAILABLE,
                trackingQuality = TrackingQuality.UNAVAILABLE,
                message = message,
            ),
        )
    }

    private fun publishDatasetState() {
        publish(
            lastState.copy(
                datasetMode = datasetMode,
                latestDatasetName = latestDataset?.name,
                datasetMessage = datasetMessage,
            ).withDiagnostics(diagnostics.snapshot()),
        )
    }

    private fun publishDatasetError(message: String) {
        datasetMode = ArDatasetMode.ERROR
        datasetMessage = message
        publishDatasetState()
    }

    private fun datasetDirectory(): File =
        (context.getExternalFilesDir("ar-datasets") ?: File(context.filesDir, "ar-datasets"))

    private fun findLatestDataset(): File? = datasetDirectory()
        .listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
        ?.maxByOrNull(File::lastModified)

    private fun displayRotationDegrees(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun publish(state: ArRuntimeState) {
        if (state == lastState && state != ArRuntimeState()) return
        lastState = state
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            stateListener(state)
        } else {
            post { stateListener(state) }
        }
    }

    private fun ArRuntimeState.withDiagnostics(
        snapshot: ArTrackingDiagnosticsSnapshot,
    ): ArRuntimeState = copy(
        trackingLossCount = snapshot.totalTrackingLossCount,
        unexpectedTrackingLossCount = snapshot.unexpectedTrackingLossCount,
        expectedTransitionLossCount = snapshot.expectedTransitionLossCount,
        lastRecoveryMillis = snapshot.lastRecoveryMillis,
        trackingFailureReason = snapshot.trackingFailureReason,
        lifecycleState = snapshot.lifecycleState,
        displayInteractive = powerManager.isInteractive,
        sessionGeneration = snapshot.sessionGeneration,
        transitionReason = snapshot.transitionReason,
        expectedSessionTransition = snapshot.expectedSessionTransition,
    )

    private fun logDiagnosticEvent(
        event: String,
        snapshot: ArTrackingDiagnosticsSnapshot,
    ) {
        Log.i(
            DIAGNOSTIC_LOG_TAG,
            "event=$event lifecycle=${snapshot.lifecycleState.name} " +
                "interactive=${powerManager.isInteractive} generation=${snapshot.sessionGeneration} " +
                "dataset=${datasetMode.name} transition=${snapshot.transitionReason.name} " +
                "expected_transition=${snapshot.expectedSessionTransition} " +
                "losses=${snapshot.totalTrackingLossCount} " +
                "unexpected_losses=${snapshot.unexpectedTrackingLossCount} " +
                "expected_losses=${snapshot.expectedTransitionLossCount}",
        )
    }

    private data class GuidanceInput(
        val route: List<GeoCoordinate>,
        val user: GeoCoordinate?,
        val accuracyMeters: Float?,
        val headingDegrees: Float?,
    )

    private companion object {
        const val DIAGNOSTIC_LOG_TAG = "NaViAR"
        const val DIAGNOSTIC_LOG_INTERVAL_MILLIS = 5_000L
    }
}

private data class FrameTelemetry(
    val trackingQuality: TrackingQuality,
    val depthActive: Boolean,
    val diagnostics: ArTrackingDiagnosticsSnapshot,
    val frameTimeMillis: Float,
    val message: String?,
    val recordingStatus: RecordingStatus,
    val playbackStatus: PlaybackStatus,
)

private class ArCoreRenderer(
    private val diagnostics: ArTrackingDiagnostics,
    private val onTelemetry: (FrameTelemetry) -> Unit,
    private val onRenderSample: (PocRenderSample)->Unit,
) : GLSurfaceView.Renderer {
    @Volatile
    var session: Session? = null

    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    private val cameraRenderer = CameraBackgroundRenderer()
    private val ribbonRenderer = RouteRibbonRenderer()
    @Volatile
    private var textureSession: Session? = null
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastTrackingState: Boolean? = null
    private var lastTelemetryAt = 0L
    @Volatile var perceptionListener: ((SpatialFrameContext,PerceptionFrameLease)->Unit)?=null
    @Volatile var pocCalibration: MapCalibration?=null
    @Volatile var cameraSensorOrientation: Int=0
    val trackingEpoch=java.util.concurrent.atomic.AtomicLong(0)
    private val perceptionCapture=ArPerceptionCapture()
    private var lastCaptureTimestamp=0L
    private data class AnchoredReference(val anchor: Anchor,val reference: CalibrationReference,val epoch: Long)
    private var referenceA: AnchoredReference?=null
    private var referenceB: AnchoredReference?=null
    private var lastCameraPose: Pose?=null
    private var lastCameraTimestamp=0L
    private var lastCameraReceivedAt=0L
    private var lastCameraEpoch=-1L
    private var currentPocRoute: List<GeoCoordinate> = emptyList()
    private var currentPocRouteRevision=0
    private var lastRenderStartedAt: Long?=null
    private val renderTimingReset=java.util.concurrent.atomic.AtomicBoolean(true)

    /** Called on the GL thread or while the GLSurfaceView render thread is paused. */
    fun resetRenderTiming() {renderTimingReset.set(true)}

    /** Called on GL, or after GLSurfaceView.onPause has stopped the GL thread. */
    fun clearPocAlignment() {
        referenceA?.anchor?.let{runCatching{it.detach()}}
        referenceB?.anchor?.let{runCatching{it.detach()}}
        referenceA=null;referenceB=null;pocCalibration=null
        lastCameraPose=null;lastCameraEpoch=-1
        currentPocRoute=emptyList()
        currentPocRouteRevision=0
        trackingEpoch.incrementAndGet()
        ribbonRenderer.setPocRoute(emptyList(),null)
    }

    fun beginPocSession() {
        clearPocAlignment()
        lastTrackingState=null
        lastCaptureTimestamp=0L
        resetRenderTiming()
    }

    /** Both Anchor creation and reads of Anchor poses happen on the render thread. */
    fun capturePocReference(geo: GeoCoordinate,accuracyMeters: Double,label: String,second: Boolean): MapCalibration? {
        val activeSession=checkNotNull(session){"AR 세션이 준비되지 않았습니다."}
        val pose=checkNotNull(lastCameraPose){"카메라 추적을 기다리세요."}
        check(lastCameraEpoch==trackingEpoch.get() && SystemClock.elapsedRealtime()-lastCameraReceivedAt in 0..500) {"최근의 유효한 카메라 추적이 필요합니다."}
        require(label.isNotBlank() && accuracyMeters in 0.01..0.5) {"기준점 이름과 0.01~0.5m 위치 오차를 입력하세요."}
        require(geo.latitude in -89.0..89.0 && geo.longitude in -180.0..180.0) {"기준점 좌표가 유효하지 않습니다."}
        if(!second) {
            referenceA?.anchor?.detach();referenceB?.anchor?.detach()
            referenceA=null;referenceB=null;pocCalibration=null;currentPocRoute=emptyList()
            currentPocRouteRevision=0
            ribbonRenderer.setPocRoute(emptyList(),null)
            val anchor=activeSession.createAnchor(pose)
            referenceA=AnchoredReference(anchor,CalibrationReference(geo,pose.worldPosition(),accuracyMeters,label),trackingEpoch.get())
            return null
        }
        val first=checkNotNull(referenceA){"기준점 A를 먼저 기록하세요."}
        check(first.epoch==trackingEpoch.get() && first.anchor.trackingState==TrackingState.TRACKING) {"기준점 A 추적이 끊겼습니다. 두 기준점을 다시 기록하세요."}
        val anchor=activeSession.createAnchor(pose)
        try {
            val secondReference=CalibrationReference(geo,anchor.pose.worldPosition(),accuracyMeters,label)
            val calibration=MapCalibration.fromReferences(first.reference.copy(world=first.anchor.pose.worldPosition()),secondReference,lastCameraTimestamp,"cal-${java.util.UUID.randomUUID()}")
            referenceB?.anchor?.detach()
            referenceB=AnchoredReference(anchor,secondReference,trackingEpoch.get())
            pocCalibration=calibration
            return calibration
        } catch(error: Throwable) {anchor.detach();throw error}
    }

    private fun refreshPocAlignment(): MapCalibration? {
        val first=referenceA ?: return null
        val second=referenceB
        if(first.epoch!=trackingEpoch.get() || first.anchor.trackingState!=TrackingState.TRACKING ||
            (second!=null && (second.epoch!=trackingEpoch.get() || second.anchor.trackingState!=TrackingState.TRACKING))) {
            clearPocAlignment();return null
        }
        val prior=pocCalibration ?: return null
        if(second==null)return null
        val updated=runCatching {prior.withTrackedReferences(first.anchor.pose.worldPosition(),second.anchor.pose.worldPosition())}.getOrElse {
            clearPocAlignment();return null
        }
        pocCalibration=updated
        // Geometry stays in map coordinates while its world transform follows this frame's Anchors.
        ribbonRenderer.setPocRoute(currentPocRoute,updated)
        return updated
    }

    fun setPocRoute(route: List<GeoCoordinate>,calibration: MapCalibration?,routeRevision: Int) {
        val current=pocCalibration?.takeIf{it.revision==calibration?.revision}
        if(current==null) {
            // An old inference or network reply must not erase or revive a newer alignment.
            if(calibration==null) {currentPocRoute=emptyList();currentPocRouteRevision=0;ribbonRenderer.setPocRoute(emptyList(),null)}
            return
        }
        if(routeRevision>0 && currentPocRouteRevision>routeRevision)return
        currentPocRoute=route
        currentPocRouteRevision=if(route.size>=2)routeRevision else 0
        ribbonRenderer.setPocRoute(route,current)
    }

    fun setGuidance(path: ForwardGuidancePath?, headingDegrees: Float?) {
        ribbonRenderer.setGuidance(path, headingDegrees)
    }

    fun invalidateCameraTexture() {
        textureSession = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        resetRenderTiming()
        GLES20.glClearColor(0.03f, 0.08f, 0.18f, 1f)
        cameraRenderer.createOnGlThread()
        ribbonRenderer.createOnGlThread()
        textureSession = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        resetRenderTiming()
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartedAt=System.nanoTime()
        val previousRender=if(renderTimingReset.getAndSet(false))null else lastRenderStartedAt
        val frameIntervalMillis=previousRender?.let{previous->
            (frameStartedAt-previous).takeIf{it>=0}?.div(1_000_000.0)
        }
        lastRenderStartedAt=frameStartedAt
        var renderTracking=false
        var drawSubmitted=false
        try {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val activeSession = session ?: return
        if (textureSession !== activeSession) {
            activeSession.setCameraTextureName(cameraRenderer.textureId)
            textureSession = activeSession
        }

        activeSession.setDisplayGeometry(
            displayRotation,
            viewportWidth,
            viewportHeight,
        )
        val frame = try {
            activeSession.update()
        } catch (_: TextureNotSetException) {
            textureSession = null
            return
        } catch (_: CameraNotAvailableException) {
            publishUnavailableTelemetry(frameStartedAt)
            return
        }

        cameraRenderer.draw(frame)
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        renderTracking=tracking
        val listener=perceptionListener
        if(!tracking) {
            if(lastTrackingState==true || referenceA!=null || pocCalibration!=null)clearPocAlignment()
            lastCameraPose=null
        } else {
            refreshPocAlignment()
            lastCameraPose=camera.pose
            lastCameraTimestamp=frame.timestamp
            lastCameraReceivedAt=SystemClock.elapsedRealtime()
            lastCameraEpoch=trackingEpoch.get()
        }
        if(listener!=null && frame.timestamp-lastCaptureTimestamp>=200_000_000L) {
            lastCaptureTimestamp=frame.timestamp
            if(!tracking) pocCalibration=null
            try {
                val displayDegrees=when(displayRotation){Surface.ROTATION_90->90;Surface.ROTATION_180->180;Surface.ROTATION_270->270;else->0}
                val rotation=(cameraSensorOrientation-displayDegrees+360)%360
                perceptionCapture.capture(frame,rotation,pocCalibration,"Live",trackingEpoch.get(),listener)
            } catch(_: NotYetAvailableException) {
                // Camera/Depth may not have produced an image yet; no fabricated spatial sample.
            } catch(e: Exception) {Log.e("NaViCapture","capture_failed",e)}
        }
        val failureReason = if (tracking) null else camera.trackingFailureReason.name
        val diagnosticsSnapshot = diagnostics.onTrackingObservation(
            tracking = tracking,
            failureReason = failureReason,
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        )
        val depthActive = if (tracking) frame.hasDepthImage() else false
        drawSubmitted=if (tracking) {
            ribbonRenderer.draw(
                cameraPose = camera.pose,
                camera = camera,
                frame = frame,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
            )
        } else false

        val now = SystemClock.elapsedRealtime()
        if (now - lastTelemetryAt >= TELEMETRY_INTERVAL_MILLIS || tracking != lastTrackingState) {
            lastTelemetryAt = now
            onTelemetry(
                FrameTelemetry(
                    trackingQuality = if (tracking) TrackingQuality.TRACKING else TrackingQuality.DEGRADED,
                    depthActive = depthActive,
                    diagnostics = diagnosticsSnapshot,
                    frameTimeMillis = (System.nanoTime() - frameStartedAt) / 1_000_000f,
                    message = failureReason,
                    recordingStatus = activeSession.recordingStatus,
                    playbackStatus = activeSession.playbackStatus,
                ),
            )
        }
        lastTrackingState = tracking
        } finally {
            onRenderSample(PocRenderSample(
                timestampNanos=frameStartedAt,
                routeRevision=currentPocRouteRevision,
                frameIntervalMillis=frameIntervalMillis,
                renderWorkMillis=(System.nanoTime()-frameStartedAt)/1_000_000.0,
                drawSubmitted=drawSubmitted,
                tracking=renderTracking,
            ))
        }
    }

    private fun publishUnavailableTelemetry(frameStartedAt: Long) {
        if(lastCameraPose!=null || referenceA!=null || pocCalibration!=null)clearPocAlignment()
        lastTrackingState=false
        val diagnosticsSnapshot = diagnostics.onTrackingObservation(
            tracking = false,
            failureReason = "CAMERA_NOT_AVAILABLE",
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
        )
        onTelemetry(
            FrameTelemetry(
                trackingQuality = TrackingQuality.UNAVAILABLE,
                depthActive = false,
                diagnostics = diagnosticsSnapshot,
                frameTimeMillis = (System.nanoTime() - frameStartedAt) / 1_000_000f,
                message = "CAMERA_NOT_AVAILABLE",
                recordingStatus = RecordingStatus.NONE,
                playbackStatus = PlaybackStatus.NONE,
            ),
        )
    }

    private fun Frame.hasDepthImage(): Boolean = try {
        acquireDepthImage16Bits().use { true }
    } catch (_: NotYetAvailableException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    private companion object {
        const val TELEMETRY_INTERVAL_MILLIS = 300L
    }
}

private class CameraBackgroundRenderer {
    var textureId: Int = 0
        private set

    private var program = 0
    private var positionAttribute = 0
    private var texCoordAttribute = 0
    private var textureUniform = 0
    private val quadCoordinates = floatBufferOf(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )
    private val textureCoordinates = floatBufferOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )

        program = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordAttribute = GLES20.glGetAttribLocation(program, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(program, "u_Texture")
    }

    fun draw(frame: Frame) {
        if (frame.timestamp == 0L) return
        if (frame.hasDisplayGeometryChanged()) {
            quadCoordinates.position(0)
            textureCoordinates.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoordinates,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureCoordinates,
            )
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureUniform, 0)

        quadCoordinates.position(0)
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, quadCoordinates)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        textureCoordinates.position(0)
        GLES20.glVertexAttribPointer(texCoordAttribute, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)
        GLES20.glEnableVertexAttribArray(texCoordAttribute)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisableVertexAttribArray(texCoordAttribute)
        GLES20.glDepthMask(true)
    }

    private companion object {
        const val CAMERA_VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """
    }
}

private class RouteRibbonRenderer {
    private var program = 0
    private var positionAttribute = 0
    private var mvpUniform = 0
    private var colorUniform = 0
    private var guidance: ForwardGuidancePath? = null
    private var pocRoute: List<GeoCoordinate>?=null
    private var pocCalibration: MapCalibration?=null
    private var headingDegrees: Float? = null
    private var guidanceRevision = 0
    private var anchoredRevision = -1
    private var vertices: FloatBuffer? = null
    private var vertexCount = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun createOnGlThread() {
        program = createProgram(RIBBON_VERTEX_SHADER, RIBBON_FRAGMENT_SHADER)
        positionAttribute = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
        anchoredRevision = -1
    }

    fun setGuidance(path: ForwardGuidancePath?, heading: Float?) {
        guidance = path
        headingDegrees = heading
        guidanceRevision += 1
    }

    fun setPocRoute(route: List<GeoCoordinate>,calibration: MapCalibration?) {
        pocRoute=route;pocCalibration=calibration;guidanceRevision++
    }

    fun draw(
        cameraPose: Pose,
        camera: com.google.ar.core.Camera,
        frame: Frame,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Boolean {
        if(pocRoute!=null) {
            val c=pocCalibration ?: return false
            if(c.errorAt(Vec3(cameraPose.tx().toDouble(),cameraPose.ty().toDouble(),cameraPose.tz().toDouble()),frame.timestamp)>1.5) return false
        }
        if (anchoredRevision != guidanceRevision) {
            vertices = buildWorldRibbon(cameraPose, frame, viewportWidth, viewportHeight)
            vertexCount = (vertices?.capacity() ?: 0) / 3
            anchoredRevision = guidanceRevision
        }
        val activeVertices = vertices ?: return false
        if (vertexCount < 4) return false

        camera.getProjectionMatrix(projection, 0, 0.1f, 100f)
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, mvp, 0)
        GLES20.glUniform4f(colorUniform, 0.16f, 0.39f, 0.96f, 0.82f)
        activeVertices.position(0)
        GLES20.glVertexAttribPointer(positionAttribute, 3, GLES20.GL_FLOAT, false, 0, activeVertices)
        GLES20.glEnableVertexAttribArray(positionAttribute)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionAttribute)
        GLES20.glDisable(GLES20.GL_BLEND)
        return true
    }

    private fun buildWorldRibbon(
        cameraPose: Pose,
        frame: Frame,
        viewportWidth: Int,
        viewportHeight: Int,
    ): FloatBuffer? {
        val cameraOrigin = cameraPose.translation
        if(pocRoute!=null) {
            val c=pocCalibration ?: return null
            val ground=frame.hitTest(viewportWidth*0.5f,viewportHeight*0.75f).firstOrNull {
                val plane=it.trackable as? com.google.ar.core.Plane
                plane!=null && plane.trackingState==TrackingState.TRACKING && plane.type==com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING && plane.isPoseInPolygon(it.hitPose)
            } ?: return null
            val centres=pocRoute.orEmpty().map { c.world(it,ground.hitPose.ty().toDouble()+0.02) }
            if(centres.size<2)return null
            val values=ArrayList<Float>()
            centres.forEachIndexed { i,p ->
                val a=centres[(i-1).coerceAtLeast(0)];val b=centres[(i+1).coerceAtMost(centres.lastIndex)]
                val dx=b.x-a.x;val dz=b.z-a.z;val len=hypot(dx,dz).coerceAtLeast(0.001)
                val nx=-dz/len*RIBBON_HALF_WIDTH_METERS;val nz=dx/len*RIBBON_HALF_WIDTH_METERS
                values.addAll(listOf((p.x+nx).toFloat(),p.y.toFloat(),(p.z+nz).toFloat(),(p.x-nx).toFloat(),p.y.toFloat(),(p.z-nz).toFloat()))
            }
            return floatBufferOf(*values.toFloatArray())
        }
        val path = guidance ?: return null
        val heading = headingDegrees ?: return null
        if (path.points.size < 2) return null
        val cameraRight = cameraPose.xAxis.horizontalNormalized() ?: return null
        val cameraForward = cameraPose.zAxis
            .map { -it }
            .toFloatArray()
            .horizontalNormalized() ?: return null
        val floorY = estimateFloorY(frame, cameraOrigin[1], viewportWidth, viewportHeight)
        val headingRadians = heading * PI.toFloat() / 180f
        val headingCos = cos(headingRadians)
        val headingSin = sin(headingRadians)

        val centres = path.points.map { point ->
            val rightMeters = point.eastMeters * headingCos - point.northMeters * headingSin
            val forwardMeters = point.eastMeters * headingSin + point.northMeters * headingCos
            WorldPoint(
                x = cameraOrigin[0] + cameraRight[0] * rightMeters + cameraForward[0] * forwardMeters,
                y = floorY,
                z = cameraOrigin[2] + cameraRight[2] * rightMeters + cameraForward[2] * forwardMeters,
            )
        }

        val values = ArrayList<Float>(centres.size * 6)
        centres.forEachIndexed { index, centre ->
            val before = centres[(index - 1).coerceAtLeast(0)]
            val after = centres[(index + 1).coerceAtMost(centres.lastIndex)]
            val dx = after.x - before.x
            val dz = after.z - before.z
            val length = hypot(dx, dz).coerceAtLeast(0.001f)
            val normalX = -dz / length * RIBBON_HALF_WIDTH_METERS
            val normalZ = dx / length * RIBBON_HALF_WIDTH_METERS
            values += centre.x + normalX
            values += centre.y
            values += centre.z + normalZ
            values += centre.x - normalX
            values += centre.y
            values += centre.z - normalZ
        }
        return floatBufferOf(*values.toFloatArray())
    }

    private fun estimateFloorY(
        frame: Frame,
        cameraY: Float,
        viewportWidth: Int,
        viewportHeight: Int,
    ): Float {
        val hits = frame.hitTest(viewportWidth * 0.5f, viewportHeight * 0.72f)
        val groundHit = hits.firstOrNull { it.trackable.trackingState == TrackingState.TRACKING }
        return groundHit?.hitPose?.ty() ?: (cameraY - DEFAULT_CAMERA_HEIGHT_METERS)
    }

    private data class WorldPoint(val x: Float, val y: Float, val z: Float)

    private companion object {
        const val RIBBON_HALF_WIDTH_METERS = 0.28f
        const val DEFAULT_CAMERA_HEIGHT_METERS = 1.35f

        const val RIBBON_VERTEX_SHADER = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_Mvp * a_Position;
            }
        """

        const val RIBBON_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """
    }
}

private fun FloatArray.horizontalNormalized(): FloatArray? {
    if (size < 3) return null
    val length = hypot(this[0], this[2])
    if (length < 0.001f) return null
    return floatArrayOf(this[0] / length, 0f, this[2] / length)
}

private fun Pose.worldPosition()=Vec3(tx().toDouble(),ty().toDouble(),tz().toDouble())

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }

private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
    val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
    return GLES20.glCreateProgram().also { program ->
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "OpenGL program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
    }
}

private fun compileShader(type: Int, source: String): Int = GLES20.glCreateShader(type).also { shader ->
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val status = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] == GLES20.GL_TRUE) { "OpenGL shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
}

private fun UnavailableException.toUserMessage(): String = when (this) {
    is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException -> "이 기기는 ARCore를 지원하지 않습니다."
    is com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException -> "Google Play Services for AR이 설치되지 않았습니다."
    is com.google.ar.core.exceptions.UnavailableApkTooOldException -> "Google Play Services for AR 업데이트가 필요합니다."
    is com.google.ar.core.exceptions.UnavailableSdkTooOldException -> "앱의 ARCore SDK 업데이트가 필요합니다."
    is com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException -> "ARCore 설치가 취소되었습니다."
    else -> "ARCore 세션을 시작할 수 없습니다."
}
