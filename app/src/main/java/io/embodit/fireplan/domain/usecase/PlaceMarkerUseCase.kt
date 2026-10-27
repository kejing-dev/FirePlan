package io.embodit.fireplan.domain.usecase

import io.embodit.fireplan.domain.model.Marker
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.PlaneSurface
import io.embodit.fireplan.domain.model.SurfaceKind
import io.embodit.fireplan.domain.model.Vec3

/**
 * Where a pinch puts a marker. v0.1.1 (user, 2026-10-26: "smoke detector and exit can't be placed"):
 * nothing is refused any more. Every surface of the room box (floor, ceiling, walls) and every detected
 * table top takes every kind; the marker goes where its kind belongs, straight above / below or at the
 * foot of the wall that was pointed at:
 * - fire spot: a detected TABLE / CABINET top, else the floor (below the point, or at the wall's foot);
 * - extinguisher, exit: the floor (a wall or door = its foot, 0.3 m into the room);
 * - detector: the ceiling (above the point). A wall pointed at within 0.6 m of the ceiling takes a
 *   wall-mounted detector; lower on a wall it goes on the ceiling 0.6 m in from that wall.
 * [floorY] / [ceilingY] are the room's.
 */
class PlaceMarkerUseCase(private val detectorCheck: DetectorCheckUseCase = DetectorCheckUseCase()) {

    sealed interface Outcome {
        data class Place(val marker: Marker) : Outcome

        data object Refused : Outcome
    }

    fun place(
        id: String,
        kind: MarkerKind,
        plane: PlaneSurface,
        point: Vec3,
        head: Vec3,
        planes: Collection<PlaneSurface>,
        floorY: Float,
        ceilingY: Float,
    ): Outcome {
        val vertical = plane.kind == SurfaceKind.WALL || plane.kind == SurfaceKind.DOOR || (!plane.horizontal && plane.kind != SurfaceKind.FLOOR && plane.kind != SurfaceKind.CEILING)
        val inward = if (vertical) inwardOf(plane, point, head) ?: return Outcome.Refused else null
        fun floorBelow() = Vec3(point.x, floorY, point.z)
        fun wallFoot(offset: Float) = Vec3(point.x, floorY, point.z) + inward!! * offset
        return when (kind) {
            MarkerKind.FIRE ->
                when {
                    isTopFace(plane) -> Outcome.Place(Marker(id, kind, point, onTable = true))
                    vertical -> Outcome.Place(Marker(id, kind, wallFoot(DOOR_OFFSET_M)))
                    else -> Outcome.Place(Marker(id, kind, floorBelow()))
                }
            MarkerKind.EXTINGUISHER, MarkerKind.EXIT ->
                Outcome.Place(Marker(id, kind, if (vertical) wallFoot(DOOR_OFFSET_M) else floorBelow()))
            MarkerKind.DETECTOR -> {
                val onWall = vertical && point.y >= ceilingY - WALL_MOUNT_ZONE_M
                val pos =
                    when {
                        onWall -> point
                        vertical -> Vec3(point.x, ceilingY, point.z) + inward!! * DETECTOR_IN_FROM_WALL_M
                        else -> Vec3(point.x, ceilingY, point.z)
                    }
                Outcome.Place(
                    Marker(
                        id,
                        kind,
                        pos,
                        onCeiling = !onWall,
                        belowCeilingM = if (onWall) (ceilingY - point.y).coerceAtLeast(0f) else null,
                        wallDistM = detectorCheck.wallDistance(pos, planes),
                    )
                )
            }
        }
    }

    /** The wall's level normal turned towards the head (into the room), or null for a level plane. */
    private fun inwardOf(plane: PlaneSurface, point: Vec3, head: Vec3): Vec3? {
        val n = plane.normal
        var flat = Vec3(n.x, 0f, n.z)
        if (flat.length < 1e-3f) return null
        flat = flat.normalized()
        if ((head - point).dot(flat) < 0f) flat = -flat
        return flat
    }

    /** A TABLE / CABINET plane whose normal points up: its top face. */
    private fun isTopFace(plane: PlaneSurface): Boolean =
        (plane.kind == SurfaceKind.TABLE || plane.kind == SurfaceKind.CABINET) && plane.horizontal

    companion object {
        const val DOOR_OFFSET_M = 0.3f
        const val WALL_MOUNT_ZONE_M = 0.6f
        const val DETECTOR_IN_FROM_WALL_M = 0.6f

        fun hasCeiling(planes: Collection<PlaneSurface>): Boolean = planes.any { it.kind == SurfaceKind.CEILING }

        /** Height of the floor: the lowest FLOOR plane, or null when none is known yet. */
        fun floorHeight(planes: Collection<PlaneSurface>): Float? =
            planes.filter { it.kind == SurfaceKind.FLOOR && it.vertices.isNotEmpty() }.minOfOrNull { it.centroid.y }
    }
}
