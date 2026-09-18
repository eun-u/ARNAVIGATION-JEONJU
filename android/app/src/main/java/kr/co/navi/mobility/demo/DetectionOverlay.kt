package kr.co.navi.mobility.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.navi.mobility.guidance.contract.DetectedRegion
import kr.co.navi.mobility.guidance.contract.SpatialCapture
import java.util.Locale

/** Displays real detector output; colors identify object classes, not route obstruction. */
@Composable
internal fun DetectionOverlay(
    detections: List<DetectedRegion>,
    capture: SpatialCapture? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    // A live image needs ARCore's viewport transform. Replay is already upright.
    if (capture != null && capture.imageToView == null) return
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier) {
        val padding = 6.dp.toPx()
        if (size.width <= padding * 4 || size.height <= padding * 4) return@Canvas
        clipRect {
            detections.forEach { detection ->
                val bounds = detection.bounds
                val corners = listOf(
                    bounds.left to bounds.top, bounds.right to bounds.top,
                    bounds.right to bounds.bottom, bounds.left to bounds.bottom,
                ).map { (u, v) ->
                    if (capture == null) {
                        Offset(u * size.width, v * size.height)
                    } else {
                        // Undo inference rotation before applying the actual preview crop.
                        val cpu = when (capture.rotationDegrees) {
                            90 -> v to 1 - u
                            180 -> 1 - u to 1 - v
                            270 -> 1 - v to u
                            else -> u to v
                        }
                        val view = capture.imageToView!!.uv(
                            cpu.first * capture.intrinsics.width.toDouble(),
                            cpu.second * capture.intrinsics.height.toDouble(),
                        )
                        Offset((view.first * size.width).toFloat(), (view.second * size.height).toFloat())
                    }
                }
                if (corners.any { !it.x.isFinite() || !it.y.isFinite() }) return@forEach
                val left = corners.minOf { it.x }
                val top = corners.minOf { it.y }
                val right = corners.maxOf { it.x }
                val bottom = corners.maxOf { it.y }
                if (right <= 0 || bottom <= 0 || left >= size.width || top >= size.height ||
                    right <= left || bottom <= top) return@forEach

                val color = detectionColor(detection.label)
                (corners + corners.first()).zipWithNext().forEach { (from, to) ->
                    drawLine(color.copy(alpha = 0.65f), from, to, 1.dp.toPx())
                    val vector = to - from
                    val length = vector.getDistance()
                    if (length > 0f) {
                        val corner = vector * (minOf(12.dp.toPx(), length * 0.25f) / length)
                        drawLine(color, from, from + corner, 3.dp.toPx())
                        drawLine(color, to - corner, to, 3.dp.toPx())
                    }
                }

                // A score is detector output, not measured accuracy or a safety judgment.
                val label = "${detectionName(detection.label)} · 점수 ${String.format(Locale.ROOT, "%.2f", detection.confidence)}"
                val text = textMeasurer.measure(
                    text = label,
                    style = TextStyle(color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = (size.width - padding * 4).toInt()),
                )
                val badgeSize = Size(text.size.width + padding * 2, text.size.height + padding)
                val badgeLeft = left.coerceIn(padding, size.width - badgeSize.width - padding)
                val aboveBox = top - badgeSize.height - 3.dp.toPx()
                val badgeTop = (if (aboveBox >= padding) aboveBox else top + 3.dp.toPx())
                    .coerceIn(padding, (size.height - badgeSize.height - padding).coerceAtLeast(padding))
                drawRoundRect(
                    Color(0xEB101B26), Offset(badgeLeft, badgeTop), badgeSize,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                drawText(text, topLeft = Offset(badgeLeft + padding, badgeTop + padding / 2))
            }
        }
    }
}

private fun detectionColor(label: String): Color = when (label) {
    "traffic_cone" -> Color(0xFFFFB34D)
    "person" -> Color(0xFF65E0FF)
    "car", "motorcycle", "bus", "train", "truck" -> Color(0xFFC2A9FF)
    "bicycle" -> Color(0xFF83E7AF)
    "traffic light", "stop sign", "fire hydrant", "parking meter", "bench" -> Color(0xFFFFDF82)
    else -> Color(0xFFF0F4F8)
}

private fun detectionName(label: String): String = when (label) {
    "traffic_cone" -> "콘"
    "person" -> "사람"
    "bicycle" -> "자전거"
    "car" -> "자동차"
    "motorcycle" -> "오토바이"
    "bus" -> "버스"
    "train" -> "기차"
    "truck" -> "트럭"
    "traffic light" -> "신호등"
    "stop sign" -> "정지 표지판"
    "fire hydrant" -> "소화전"
    "parking meter" -> "주차 요금기"
    "bench" -> "벤치"
    "bird" -> "새"
    "cat" -> "고양이"
    "dog" -> "개"
    "backpack" -> "배낭"
    "umbrella" -> "우산"
    "handbag" -> "가방"
    "suitcase" -> "여행 가방"
    "chair" -> "의자"
    "potted plant" -> "화분"
    "bottle" -> "병"
    "cup" -> "컵"
    "cell phone" -> "휴대폰"
    else -> label
}
