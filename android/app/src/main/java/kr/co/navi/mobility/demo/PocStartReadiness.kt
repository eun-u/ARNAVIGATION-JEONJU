package kr.co.navi.mobility.demo

import kr.co.navi.mobility.guidance.contract.*
import kotlin.math.hypot

internal fun pocStartReadiness(spatial: SpatialFrameContext?,requireSemantics: Boolean=true): String? {
    if(spatial?.trackingQuality!=TrackingQuality.TRACKING)return "휴대폰을 천천히 움직여 주변을 인식시키세요."
    val pose=spatial.localPose ?: return "카메라 위치를 확인하고 있습니다."
    val front=pose.transform(Vec3(0.0,0.0,-1.0))
    if(hypot(front.x-pose.xMeters,front.z-pose.zMeters)<0.35)return "휴대폰을 들어 남쪽 보행로를 향하세요."
    val capture=spatial.capture ?: return "카메라를 준비하고 있습니다."
    if(capture.groundHeightMeters==null)return "앞쪽 바닥이 보이도록 휴대폰을 천천히 움직이세요."
    if(requireSemantics && capture.semantics==null)return "보행 영역 정보를 준비하고 있습니다."
    return null
}
