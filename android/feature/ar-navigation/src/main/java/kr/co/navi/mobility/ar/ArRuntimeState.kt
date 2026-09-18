package kr.co.navi.mobility.ar

import kr.co.navi.mobility.guidance.contract.TrackingQuality

enum class ArRuntimeMode {
    CHECKING,
    INSTALL_REQUIRED,
    TRACKING,
    DEGRADED,
    UNAVAILABLE,
}

enum class ArDatasetMode {
    LIVE,
    RECORDING,
    PLAYBACK,
    PLAYBACK_FINISHED,
    ERROR,
}

/**
 * One actual GLSurfaceView render callback. The timestamp and interval use System.nanoTime,
 * not the camera clock. drawSubmitted records a ribbon GL command, not visible pixels or field accuracy.
 */
data class PocRenderSample(
    val timestampNanos: Long,
    val routeRevision: Int,
    val frameIntervalMillis: Double?,
    val renderWorkMillis: Double,
    val drawSubmitted: Boolean,
    val tracking: Boolean,
)

data class ArRuntimeState(
    val mode: ArRuntimeMode = ArRuntimeMode.CHECKING,
    val trackingQuality: TrackingQuality = TrackingQuality.WAITING,
    val depthSupported: Boolean = false,
    val depthActive: Boolean = false,
    val routeAligned: Boolean = false,
    val trackingLossCount: Int = 0,
    val unexpectedTrackingLossCount: Int = 0,
    val expectedTransitionLossCount: Int = 0,
    val lastRecoveryMillis: Long? = null,
    val trackingFailureReason: String? = null,
    val lifecycleState: ArLifecycleState = ArLifecycleState.INITIALIZED,
    val displayInteractive: Boolean = true,
    val sessionGeneration: Int = 0,
    val transitionReason: ArSessionTransitionReason = ArSessionTransitionReason.NONE,
    val expectedSessionTransition: Boolean = false,
    val frameTimeMillis: Float? = null,
    val message: String? = null,
    val datasetMode: ArDatasetMode = ArDatasetMode.LIVE,
    val latestDatasetName: String? = null,
    val datasetMessage: String? = null,
) {
    val shouldUse2dFallback: Boolean
        get() = mode != ArRuntimeMode.TRACKING || !routeAligned
}
