package kr.co.navi.mobility.guidance.contract

import java.nio.ByteBuffer

data class FrameStamp(
    val frameId: Long,
    val timestampNanos: Long,
) {
    init {
        require(frameId >= 0) { "frameId must be non-negative" }
        require(timestampNanos >= 0) { "timestampNanos must be non-negative" }
    }
}

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude is out of range" }
        require(longitude in -180.0..180.0) { "longitude is out of range" }
    }
}

data class LocalPose(
    val xMeters: Float,
    val yMeters: Float,
    val zMeters: Float,
    val quaternionX: Float,
    val quaternionY: Float,
    val quaternionZ: Float,
    val quaternionW: Float,
)

data class PoseAccuracy(
    val horizontalMeters: Double? = null,
    val verticalMeters: Double? = null,
    val yawDegrees: Double? = null,
)

enum class TrackingQuality {
    WAITING,
    TRACKING,
    DEGRADED,
    UNAVAILABLE,
}

data class SpatialFrameContext(
    val stamp: FrameStamp,
    val localPose: LocalPose?,
    val geoCoordinate: GeoCoordinate?,
    val accuracy: PoseAccuracy?,
    val trackingQuality: TrackingQuality,
    val depthAvailable: Boolean,
    val capture: SpatialCapture? = null,
)

enum class PixelFormat {
    YUV_420_888,
    RGBA_8888,
}

data class FramePlane(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

/**
 * A short-lived camera frame. Consumers must not retain plane buffers after [close].
 * The caller owns the lease and closes it after inference completes.
 */
interface PerceptionFrameLease : AutoCloseable {
    val stamp: FrameStamp
    val width: Int
    val height: Int
    val rotationDegrees: Int
    val pixelFormat: PixelFormat
    val planes: List<FramePlane>
}

data class NormalizedRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && left <= right)
        require(top in 0f..1f && bottom in 0f..1f && top <= bottom)
    }
}

data class DetectedRegion(
    val label: String,
    val confidence: Float,
    val bounds: NormalizedRegion,
    val trackId: String? = null,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank" }
        require(confidence in 0f..1f) { "confidence must be between 0 and 1" }
        require(trackId == null || trackId.isNotBlank()) { "trackId must not be blank" }
    }
}

data class PerceptionResult(
    val stamp: FrameStamp,
    val modelVersion: String,
    val inferenceMillis: Long,
    val detections: List<DetectedRegion>,
) {
    init {
        require(modelVersion.isNotBlank()) { "modelVersion must not be blank" }
        require(inferenceMillis >= 0) { "inferenceMillis must be non-negative" }
    }
}

data class ObservationEvidence(
    val labels: List<String>,
    val trackIds: List<String>,
)

data class HazardObservation(
    val observationId: String,
    val stamp: FrameStamp,
    val type: String,
    val geoCoordinate: GeoCoordinate?,
    val horizontalAccuracyMeters: Double?,
    val distanceMeters: Double?,
    val remainingWidthMeters: Double?,
    val persistenceMillis: Long,
    val dynamic: Boolean,
    val perceptionConfidence: Float,
    val modelVersion: String,
    val evidence: ObservationEvidence,
    val calibrationRevision: String? = null,
    val corridorOccupied: Boolean = false,
) {
    init {
        require(observationId.isNotBlank())
        require(type.isNotBlank())
        require(persistenceMillis >= 0)
        require(perceptionConfidence in 0f..1f)
    }
}
