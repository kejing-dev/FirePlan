package io.embodit.fireplan.domain.model

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the room's floor height comes from (factory taste profile D7). */
enum class RoomSource {
    /** A FLOOR plane was detected: the box stands on it. */
    SCAN,

    /** Nothing detected within the scan timeout: the box stands on the Stage ground (y = 0). */
    VIRTUAL,
}

/**
 * The room the markers go into (v0.1.1, factory taste profile D7): a box of [width] x [length] x
 * [height] metres standing at [floorY], its floor centre at [center] (y ignored), its front wall ahead
 * along [yawDeg] (0 = Stage -Z). It is turned into the planes the placement rules work on (one FLOOR,
 * one CEILING, four WALLs), so a pinch anywhere on the floor, the ceiling or a wall lands without any
 * real plane being detected. Only the floor and the ceiling height are ever taken from plane detection.
 * The Stage's y = 0 is the physical ground, hence the default floor at 0.
 */
data class RoomBox(
    val center: Vec3,
    val yawDeg: Float,
    val width: Float = DEFAULT_W,
    val length: Float = DEFAULT_L,
    val height: Float = DEFAULT_H,
    val floorY: Float = 0f,
) {
    /** Unit level direction towards the front wall. */
    val forward: Vec3
        get() {
            val r = Math.toRadians(yawDeg.toDouble())
            return Vec3(sin(r).toFloat(), 0f, -cos(r).toFloat())
        }

    val right: Vec3
        get() = forward.let { Vec3(-it.z, 0f, it.x) }

    val ceilingY: Float
        get() = floorY + height

    /** Floor corners front-left, front-right, back-right, back-left (at [floorY]). */
    fun corners(): List<Vec3> {
        val c = Vec3(center.x, floorY, center.z)
        val f = forward * (length / 2f)
        val r = right * (width / 2f)
        return listOf(c + f - r, c + f + r, c - f + r, c - f - r)
    }

    val planes: List<PlaneSurface> by lazy {
        val (fl, fr, br, bl) = corners()
        val up = Vec3(0f, height, 0f)
        fun quad(id: String, kind: SurfaceKind, a: Vec3, b: Vec3, c: Vec3, d: Vec3) = PlaneSurface(id, kind, "ROOM_${kind.name}", listOf(a, b, c, d), QUAD)
        listOf(
            quad(ID_FLOOR, SurfaceKind.FLOOR, fl, fr, br, bl),
            quad(ID_CEILING, SurfaceKind.CEILING, fl + up, fr + up, br + up, bl + up),
            quad(ID_FRONT, SurfaceKind.WALL, fl, fr, fr + up, fl + up),
            quad(ID_RIGHT, SurfaceKind.WALL, fr, br, br + up, fr + up),
            quad(ID_BACK, SurfaceKind.WALL, br, bl, bl + up, br + up),
            quad(ID_LEFT, SurfaceKind.WALL, bl, fl, fl + up, bl + up),
        )
    }

    /** Width grows to both sides, length backwards (the front wall stays), height upwards. */
    fun resized(dw: Float = 0f, dl: Float = 0f, dh: Float = 0f): RoomBox {
        val l = (length + dl).coerceIn(MIN_L, MAX_L).round2()
        return copy(
            center = center - forward * ((l - length) / 2f),
            width = (width + dw).coerceIn(MIN_W, MAX_W).round2(),
            length = l,
            height = (height + dh).coerceIn(MIN_H, MAX_H).round2(),
        )
    }

    /** Moved [right] / [ahead] metres along the viewer's level heading [facing], turned [turnDeg] about its centre. */
    fun moved(facing: Vec3, right: Float, ahead: Float, turnDeg: Float = 0f): RoomBox {
        val f = level(facing)
        val r = Vec3(-f.z, 0f, f.x)
        var yaw = yawDeg + turnDeg
        while (yaw > 180f) yaw -= 360f
        while (yaw <= -180f) yaw += 360f
        return copy(center = center + r * right + f * ahead, yawDeg = yaw)
    }

    /** True when [p] is inside the box's floor outline (grown by [margin]). */
    fun containsFlat(p: Vec3, margin: Float = 0f): Boolean {
        val d = Vec3(p.x - center.x, 0f, p.z - center.z)
        return kotlin.math.abs(d.dot(forward)) <= length / 2f + margin && kotlin.math.abs(d.dot(right)) <= width / 2f + margin
    }

    val label: String
        get() = String.format(Locale.US, "%.1f × %.1f × %.1f m", width, length, height)

    private fun Float.round2(): Float = Math.round(this * 100f) / 100f

    companion object {
        /** A living room / small office: the viewer stands inside, 1.5 m from the back wall. */
        const val DEFAULT_W = 5.0f
        const val DEFAULT_L = 6.0f
        const val DEFAULT_H = 2.7f
        const val MIN_W = 2.0f
        const val MAX_W = 15.0f
        const val MIN_L = 2.0f
        const val MAX_L = 15.0f
        const val MIN_H = 2.0f
        const val MAX_H = 5.0f
        const val SIZE_STEP_M = 0.5f
        const val HEIGHT_STEP_M = 0.1f
        const val MOVE_STEP_M = 0.25f
        const val TURN_STEP_DEG = 5f
        private const val BEHIND_M = 1.5f

        const val ID_FLOOR = "room_floor"
        const val ID_CEILING = "room_ceiling"
        const val ID_FRONT = "room_front"
        const val ID_RIGHT = "room_right"
        const val ID_BACK = "room_back"
        const val ID_LEFT = "room_left"

        private val QUAD = listOf(0, 1, 2, 0, 2, 3)

        fun isRoomPlane(id: String): Boolean = id.startsWith("room_")

        fun level(v: Vec3): Vec3 = Vec3(v.x, 0f, v.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }

        /** The box around a viewer at [at] looking along [facing]: centred across, front wall ahead, the viewer 1.5 m from the back wall. */
        fun around(at: Vec3, facing: Vec3, width: Float = DEFAULT_W, length: Float = DEFAULT_L, height: Float = DEFAULT_H, floorY: Float = 0f): RoomBox {
            val f = level(facing)
            val ahead = (length / 2f - BEHIND_M).coerceAtLeast(0f)
            val c = Vec3(at.x, floorY, at.z) + f * ahead
            val yaw = Math.toDegrees(atan2(f.x.toDouble(), -f.z.toDouble())).toFloat()
            return RoomBox(c, yaw, width, length, height, floorY)
        }
    }
}
