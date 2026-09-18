package kr.co.navi.mobility.guidance.contract

import kotlin.math.*

data class Vec3(val x: Double, val y: Double, val z: Double)
data class CameraIntrinsics(val width: Int, val height: Int, val fx: Double, val fy: Double, val cx: Double, val cy: Double)
/** CPU IMAGE_PIXELS -> TEXTURE_NORMALIZED affine, obtained from ARCore for this frame. */
data class ImageTransform(val originU: Double, val originV: Double, val xU: Double, val xV: Double, val yU: Double, val yV: Double) {
    fun uv(x: Double, y: Double) = (originU+x*xU+y*yU) to (originV+x*xV+y*yV)
}
data class DepthSamples(val width: Int, val height: Int, val millimeters: IntArray, val confidence: ByteArray, val timestampNanos: Long)
data class SemanticSamples(val width: Int, val height: Int, val labels: ByteArray, val confidence: ByteArray, val timestampNanos: Long)
data class CalibrationReference(val geo: GeoCoordinate, val world: Vec3, val accuracyMeters: Double, val label: String)

/** Coordinate transform provenance is independent of the tracking error policy. */
sealed interface RouteAlignment {
    val revision: String
    val createdTimestampNanos: Long
    val source: String
    fun mapMeters(world: Vec3): Vec3
    fun geo(world: Vec3): GeoCoordinate
    fun world(geo: GeoCoordinate, y: Double): Vec3
    /** For poc_start this is a relative tracking budget, never geographic accuracy. */
    fun errorAt(world: Vec3, timestamp: Long): Double
}

/** A measured two-reference alignment, never inferred from a single GPS fix. */
data class MapCalibration(
    override val revision: String,
    val first: CalibrationReference,
    val second: CalibrationReference,
    val yawRadians: Double,
    override val createdTimestampNanos: Long,
    val yawErrorRadians: Double,
) : RouteAlignment {
    override val source: String get() = "measured_references"
    /** Stable local map metres: east, zero, south. AR world rebasing cannot move this point. */
    override fun mapMeters(world: Vec3): Vec3 {
        val x=world.x-first.world.x; val north=-(world.z-first.world.z)
        val east=x*cos(yawRadians)+north*sin(yawRadians)
        val n=north*cos(yawRadians)-x*sin(yawRadians)
        return Vec3(east,0.0,-n)
    }
    override fun geo(world: Vec3): GeoCoordinate {
        val map=mapMeters(world)
        return GeoCoordinate(first.geo.latitude-map.z/111195.08,first.geo.longitude+map.x/(111195.08*cos(Math.toRadians(first.geo.latitude))))
    }
    /** Recompute with this frame's two tracked Anchor poses, preserving calibration identity and age. */
    fun withTrackedReferences(firstWorld: Vec3,secondWorld: Vec3): MapCalibration = fromReferences(
        first.copy(world=firstWorld),second.copy(world=secondWorld),createdTimestampNanos,revision)
    override fun world(geo: GeoCoordinate, y: Double): Vec3 {
        val e=(geo.longitude-first.geo.longitude)*111195.08*cos(Math.toRadians(first.geo.latitude))
        val n=(geo.latitude-first.geo.latitude)*111195.08
        return Vec3(first.world.x+e*cos(yawRadians)-n*sin(yawRadians),y,first.world.z-e*sin(yawRadians)-n*cos(yawRadians))
    }
    override fun errorAt(world: Vec3, timestamp: Long): Double {
        val elapsed=(timestamp-createdTimestampNanos)/1e9
        if (elapsed !in 0.0..600.0) return Double.POSITIVE_INFINITY
        val displacement=hypot(world.x-first.world.x,world.z-first.world.z)
        // Conservative configured uncertainty budget; not a measured device accuracy claim.
        return max(first.accuracyMeters,second.accuracyMeters)+displacement*sin(yawErrorRadians)+displacement*0.005+elapsed*0.001
    }
    companion object {
        fun fromReferences(a: CalibrationReference,b: CalibrationReference,stamp: Long,revision: String): MapCalibration {
            require(revision.isNotBlank() && stamp>=0) { "정합 revision과 촬영 시각이 유효하지 않습니다." }
            for(reference in listOf(a,b)) {
                require(reference.geo.latitude in -90.0..90.0 && reference.geo.longitude in -180.0..180.0 &&
                    abs(reference.geo.latitude)<89.0 && listOf(reference.world.x,reference.world.y,reference.world.z).all{it.isFinite()}) { "기준점 좌표가 유효하지 않습니다." }
            }
            require(a.label.isNotBlank() && b.label.isNotBlank()) { "기준점 이름이 필요합니다." }
            require(a.accuracyMeters in 0.01..0.5 && b.accuracyMeters in 0.01..0.5) { "기준점 위치 오차를 0.01~0.5m로 확인하세요." }
            val dx=b.world.x-a.world.x; val dz=b.world.z-a.world.z
            val east=(b.geo.longitude-a.geo.longitude)*111195.08*cos(Math.toRadians(a.geo.latitude))
            val north=(b.geo.latitude-a.geo.latitude)*111195.08
            val localLength=hypot(dx,dz);val mapLength=hypot(east,north)
            require(localLength>=5 && mapLength>=5) { "서로 5m 이상 떨어진 두 기준점이 필요합니다." }
            require(abs(localLength-mapLength)<=max(0.5,mapLength*0.05)) { "기준점 간 실제 거리와 AR 이동 거리가 맞지 않습니다." }
            val yaw=atan2(east,north)-atan2(dx,-dz)
            return MapCalibration(revision,a,b,yaw,stamp,atan2(a.accuracyMeters+b.accuracyMeters,mapLength))
        }
    }
}

data class SpatialCapture(
    val intrinsics: CameraIntrinsics,
    val imageToTexture: ImageTransform,
    val depth: DepthSamples?,
    val semantics: SemanticSamples?,
    val calibration: RouteAlignment?,
    val rotationDegrees: Int,
    val observedAtEpochMillis: Long,
    val inputMode: String,
    val groundHeightMeters: Double? = null,
    val imageToView: ImageTransform? = null,
    val trackingEpoch: Long = 0,
)

fun LocalPose.inverseTransform(world: Vec3): Vec3 = LocalPose(0f,0f,0f,-quaternionX,-quaternionY,-quaternionZ,quaternionW)
    .transform(Vec3(world.x-xMeters,world.y-yMeters,world.z-zMeters))

fun LocalPose.transform(point: Vec3): Vec3 {
    val qx=quaternionX.toDouble();val qy=quaternionY.toDouble();val qz=quaternionZ.toDouble();val qw=quaternionW.toDouble()
    val tx=2*(qy*point.z-qz*point.y);val ty=2*(qz*point.x-qx*point.z);val tz=2*(qx*point.y-qy*point.x)
    return Vec3(xMeters+point.x+qw*tx+qy*tz-qz*ty,yMeters+point.y+qw*ty+qz*tx-qx*tz,zMeters+point.z+qw*tz+qx*ty-qy*tx)
}
fun LocalPose.position() = Vec3(xMeters.toDouble(),yMeters.toDouble(),zMeters.toDouble())

data class RouteSegment(val edgeId: String, val physicalSegmentId: String, val geometry: List<GeoCoordinate>)
data class EdgeImpact(val edgeId: String, val physicalSegmentId: String, val observation: HazardObservation, val lateralErrorMeters: Double)

/** Immutable route revision consumed together by map, AR and TTS. */
data class RouteSnapshot(
    val revision: Int, val graphRevision: Int, val scopeRevision: String, val datasetRevision: String,
    val sessionId: String, val geometry: List<GeoCoordinate>, val segments: List<RouteSegment>, val distanceMeters: Double,
    val reasonCode: String? = null,
)
