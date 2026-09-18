package kr.co.navi.mobility.guidance.contract

/** A real 2D cue for a user-declared scenario, never spatial obstruction evidence. */
class PresetConeGate {
    private var first: Long?=null
    private var last: FrameStamp?=null
    private var frames=0
    private var fired=false
    fun update(stamp: FrameStamp,eligible: Boolean,detections: List<DetectedRegion>): Boolean {
        if(fired)return false
        val prior=last
        val ordered=prior==null || (stamp.frameId>prior.frameId && stamp.timestampNanos>prior.timestampNanos)
        if(!ordered){first=null;frames=0;return false}
        last=stamp
        val cone=detections.any { d->
            val center=(d.bounds.left+d.bounds.right)/2
            d.label=="traffic_cone" && d.confidence>=0.65f && center in 0.25f..0.75f && d.bounds.bottom>=0.35f
        }
        if(!eligible || !cone){first=null;frames=0;return false}
        if(first==null || (prior!=null && stamp.timestampNanos-prior.timestampNanos>500_000_000L)) {
            first=stamp.timestampNanos;frames=0
        }
        frames++
        if(frames>=4 && stamp.timestampNanos-requireNotNull(first)>=1_000_000_000L){fired=true;return true}
        return false
    }
}
