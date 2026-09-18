package kr.co.navi.mobility.demo

data class CollectionStep(val id: String, val title: String, val instruction: String, val startMs: Long, val endMs: Long)

/** Windows are collection prompts, never labels asserting that a scene actually occurred. */
object CaseCollectionPlan {
    const val durationMs = 180_000L
    val steps = listOf(
        CollectionStep("C01", "앞쪽 보행로", "앞쪽 보행로를 비추며 천천히 걸어가세요. 멈춰 있는 자전거 같은 물체가 보이면 안전한 거리에서 잠시 비춰주세요.", 0, 60_000),
        CollectionStep("C02", "길 가장자리", "길 가장자리도 천천히 비추며 걸어가세요. 길 밖의 자전거나 차량이 보이면 화면에 담아주세요.", 60_000, 120_000),
        CollectionStep("C03", "지나가는 사람", "안전한 곳에 잠시 멈춰 앞쪽 보행로를 비춰주세요. 지나가는 사람이 없어도 그대로 촬영해주세요.", 120_000, durationMs),
    )
    fun stepAt(elapsedMs: Long): CollectionStep? = steps.firstOrNull { elapsedMs >= it.startMs && elapsedMs < it.endMs }
    fun remainingSeconds(elapsedMs: Long): Int = ((durationMs - elapsedMs.coerceAtLeast(0) + 999) / 1000).coerceAtLeast(0).toInt()
}
