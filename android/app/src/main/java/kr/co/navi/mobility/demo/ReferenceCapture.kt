package kr.co.navi.mobility.demo

import kr.co.navi.mobility.guidance.contract.GeoCoordinate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal data class ReferenceInput(val coordinate: GeoCoordinate, val accuracyMeters: Double, val label: String)

internal fun parseReferenceInput(latitude: String, longitude: String, accuracy: String, label: String): ReferenceInput {
    require(label.isNotBlank()) { "기준점 이름을 입력하세요." }
    fun number(value: String, name: String): Double {
        require(value.isNotBlank()) { "$name 값을 입력하세요." }
        return value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException("$name 값을 숫자로 입력하세요.")
    }
    val lat = number(latitude, "위도")
    val lon = number(longitude, "경도")
    val error = number(accuracy, "위치 오차")
    require(abs(lat) < 89.0) { "위도는 -89도보다 크고 89도보다 작아야 합니다." }
    require(lon in -180.0..180.0) { "경도는 -180~180도 범위로 입력하세요." }
    require(error in 0.01..0.5) { "실제로 확인한 위치 오차가 0.01~0.5m인 기준점이 필요합니다." }
    return ReferenceInput(GeoCoordinate(lat, lon), error, label.trim())
}

internal class ReferenceCaptureTimeout : IllegalStateException(
    "기준점 기록 응답이 없습니다. 카메라 화면과 AR 추적 상태를 확인한 뒤 A부터 다시 기록하세요.",
)

/** A records an Anchor and legitimately returns null; no callback is a separate failure. */
internal suspend fun <T> awaitReferenceCapture(
    timeoutMillis: Long = 5_000,
    capture: ((Result<T>) -> Unit) -> Unit,
): T {
    val response = CompletableDeferred<Result<T>>()
    try {
        capture { response.complete(it) }
        val result = withTimeoutOrNull(timeoutMillis) { response.await() } ?: throw ReferenceCaptureTimeout()
        return result.getOrThrow()
    } finally {
        // A late GL callback cannot revive a timed-out or cancelled request.
        response.cancel()
    }
}
