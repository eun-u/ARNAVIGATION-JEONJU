package kr.co.navi.mobility.ai.mediapipe

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.co.navi.mobility.ai.PerceptionEngine
import kr.co.navi.mobility.ai.FramePixels
import kr.co.navi.mobility.ai.cone.ConeShapeDetector
import kr.co.navi.mobility.guidance.contract.DetectedRegion
import kr.co.navi.mobility.guidance.contract.NormalizedRegion
import kr.co.navi.mobility.guidance.contract.PerceptionFrameLease
import kr.co.navi.mobility.guidance.contract.PerceptionResult
import kr.co.navi.mobility.guidance.contract.PixelFormat

data class MediaPipeObjectDetectorConfig(
    val modelAssetPath: String = DEFAULT_MODEL_ASSET_PATH,
    val modelVersion: String = DEFAULT_PIPELINE_REVISION,
    val scoreThreshold: Float = 0.35f,
    val maxResults: Int = 10,
    val categoryAllowlist: List<String> = emptyList(),
) {
    init {
        require(modelAssetPath.isNotBlank())
        require(modelVersion.isNotBlank())
        require(scoreThreshold in 0f..1f)
        require(maxResults > 0)
    }

    companion object {
        const val DEFAULT_MODEL_ASSET_PATH = "efficientdet_lite0_int8.tflite"
        const val DEFAULT_PIPELINE_REVISION = "efficientdet-lite0-int8/1+cone-hsv-shape/1"
    }
}

/** CPU/IMAGE inference used by both live bounded-frame processing and measured replay. */
class MediaPipeObjectDetectorEngine(
    context: android.content.Context,
    private val config: MediaPipeObjectDetectorConfig = MediaPipeObjectDetectorConfig(),
) : PerceptionEngine {
    private val lock = Any()
    private var closed = false
    private val detector: ObjectDetector
    private val coneDetector = ConeShapeDetector()

    init {
        val hash=context.assets.open(config.modelAssetPath).use { stream ->
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            val bytes=ByteArray(65536)
            while(true){val n=stream.read(bytes);if(n<0)break;digest.update(bytes,0,n)}
            digest.digest().joinToString(""){"%02x".format(it)}
        }
        require(hash=="0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb") { "model_hash_mismatch" }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(config.modelAssetPath)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(config.scoreThreshold)
            .setMaxResults(config.maxResults)
            .apply {
                if (config.categoryAllowlist.isNotEmpty()) {
                    setCategoryAllowlist(config.categoryAllowlist)
                }
            }
            .build()
        detector = ObjectDetector.createFromOptions(context.applicationContext, options)
    }

    override suspend fun analyze(frame: PerceptionFrameLease): PerceptionResult =
        withContext(Dispatchers.Default) {
            val uprightBitmap = FramePixels.bitmap(frame)
            try {
                val startedAt = System.nanoTime()
                val result = synchronized(lock) {
                    check(!closed) { "MediaPipe object detector is closed" }
                    val input=BitmapImageBuilder(uprightBitmap).build()
                    try {detector.detect(input)} finally {input.close()}
                }
                val width = uprightBitmap.width.toFloat()
                val height = uprightBitmap.height.toFloat()
                val detections = result.detections().mapNotNull { detection ->
                    val category = detection.categories().maxByOrNull { it.score() }
                        ?: return@mapNotNull null
                    val box = detection.boundingBox()
                    val label = category.categoryName().ifBlank { category.displayName() }
                    if (label.isBlank()) return@mapNotNull null
                    DetectedRegion(
                        label = label,
                        confidence = category.score().coerceIn(0f, 1f),
                        bounds = NormalizedRegion(
                            left = (box.left / width).coerceIn(0f, 1f),
                            top = (box.top / height).coerceIn(0f, 1f),
                            right = (box.right / width).coerceIn(0f, 1f),
                            bottom = (box.bottom / height).coerceIn(0f, 1f),
                        ),
                    )
                }
                val pixels=IntArray(uprightBitmap.width*uprightBitmap.height)
                uprightBitmap.getPixels(pixels,0,uprightBitmap.width,0,0,uprightBitmap.width,uprightBitmap.height)
                val cones=coneDetector.detect(pixels,uprightBitmap.width,uprightBitmap.height)
                val inferenceMillis = (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND
                PerceptionResult(
                    stamp = frame.stamp,
                    modelVersion = config.modelVersion,
                    inferenceMillis = inferenceMillis,
                    detections = detections + cones,
                )
            } finally {
                uprightBitmap.recycle()
            }
        }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                detector.close()
            }
        }
    }

    private fun PerceptionFrameLease.toUprightBitmap(): Bitmap {
        require(planes.size == 1) { "RGBA frame must have exactly one plane" }
        val plane = planes.single()
        require(plane.pixelStride == BYTES_PER_PIXEL) { "RGBA pixel stride must be 4" }
        require(plane.rowStride >= width * BYTES_PER_PIXEL) { "RGBA row stride is too small" }

        val packed = java.nio.ByteBuffer.allocateDirect(width * height * BYTES_PER_PIXEL)
        val source = plane.buffer.duplicate()
        val rowBytes = width * BYTES_PER_PIXEL
        val row = ByteArray(rowBytes)
        repeat(height) { y ->
            source.position(y * plane.rowStride)
            source.get(row)
            packed.put(row)
        }
        packed.flip()

        val original = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.copyPixelsFromBuffer(packed)
        }
        if (rotationDegrees == 0) return original
        return Bitmap.createBitmap(
            original,
            0,
            0,
            original.width,
            original.height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) },
            true,
        ).also { original.recycle() }
    }

    private companion object {
        const val BYTES_PER_PIXEL = 4
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
