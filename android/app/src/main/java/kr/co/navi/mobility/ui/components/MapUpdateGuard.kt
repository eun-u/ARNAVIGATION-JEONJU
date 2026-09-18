package kr.co.navi.mobility.ui.components

/** MapLibre callbacks outlive Compose effects; only the current request may mutate the view. */
internal class MapUpdateGuard {
    private var sequence=0L
    fun begin()=Request(++sequence)

    inner class Request internal constructor(private val id: Long) {
        private var disposed=false
        fun isCurrent()=!disposed && id==sequence
        fun dispose() {disposed=true}
    }
}
