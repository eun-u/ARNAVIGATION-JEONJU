package kr.co.navi.mobility.guidance.contract

import kotlin.math.*

/** Operator places the phone at a named course start and faces its first segment.
 * Absolute/map accuracy is unknown. This transform is usable only in the fixed PoC session.
 */
data class PocStartAlignment(
    val origin: GeoCoordinate,
    val forward: GeoCoordinate,
    val anchorPose: LocalPose,
    override val createdTimestampNanos: Long,
    override val revision: String,
) : RouteAlignment {
    override val source: String get() = "poc_start"
    init {
        require(revision.startsWith("poc-start-") && createdTimestampNanos>=0)
        require(geodesicDistanceMeters(origin,forward)>=5.0)
        require(abs(origin.latitude)<89.0)
        require(listOf(anchorPose.xMeters,anchorPose.yMeters,anchorPose.zMeters,anchorPose.quaternionX,
            anchorPose.quaternionY,anchorPose.quaternionZ,anchorPose.quaternionW).all{it.isFinite()})
        val norm=sqrt(anchorPose.quaternionX*anchorPose.quaternionX+anchorPose.quaternionY*anchorPose.quaternionY+
            anchorPose.quaternionZ*anchorPose.quaternionZ+anchorPose.quaternionW*anchorPose.quaternionW)
        require(abs(norm-1f)<0.01f)
    }
    private val front=anchorPose.transform(Vec3(0.0,0.0,-1.0)).let{Vec3(it.x-anchorPose.xMeters,0.0,it.z-anchorPose.zMeters)}
    init { require(hypot(front.x,front.z)>=0.35) { "휴대폰을 바닥으로 숙이지 말고 진행 방향을 향하세요." } }
    val yawRadians=atan2((forward.longitude-origin.longitude)*cos(Math.toRadians(origin.latitude)),forward.latitude-origin.latitude)-atan2(front.x,-front.z)
    override fun mapMeters(world: Vec3): Vec3 {
        val x=world.x-anchorPose.xMeters;val north=-(world.z-anchorPose.zMeters)
        return Vec3(x*cos(yawRadians)+north*sin(yawRadians),0.0,-(north*cos(yawRadians)-x*sin(yawRadians)))
    }
    override fun geo(world: Vec3): GeoCoordinate {
        val m=mapMeters(world)
        return GeoCoordinate(origin.latitude-m.z/111195.08,origin.longitude+m.x/(111195.08*cos(Math.toRadians(origin.latitude))))
    }
    override fun world(geo: GeoCoordinate,y: Double): Vec3 {
        val e=(geo.longitude-origin.longitude)*111195.08*cos(Math.toRadians(origin.latitude))
        val n=(geo.latitude-origin.latitude)*111195.08
        return Vec3(anchorPose.xMeters+e*cos(yawRadians)-n*sin(yawRadians),y,anchorPose.zMeters-e*sin(yawRadians)-n*cos(yawRadians))
    }
    override fun errorAt(world: Vec3,timestamp: Long): Double {
        val seconds=(timestamp-createdTimestampNanos)/1e9
        if(seconds !in 0.0..600.0)return Double.POSITIVE_INFINITY
        // Configured PoC tolerance, not measured accuracy or uncertainty of the operator placement.
        return 0.20+hypot(world.x-anchorPose.xMeters,world.z-anchorPose.zMeters)*0.004+seconds*0.0005
    }
}

fun SpatialFrameContext.navigationBudgetMeters(): Double? = if(capture?.calibration?.source=="poc_start")
    accuracy?.relativeTrackingBudgetMeters else accuracy?.horizontalMeters

fun HazardObservation.navigationBudgetMeters(): Double? = if(alignmentSource=="poc_start")
    relativeTrackingBudgetMeters else horizontalAccuracyMeters
