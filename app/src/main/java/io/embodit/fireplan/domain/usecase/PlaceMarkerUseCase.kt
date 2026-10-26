package io.embodit.fireplan.domain.usecase

import io.embodit.fireplan.domain.model.Marker
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.PlaneSurface
import io.embodit.fireplan.domain.model.SurfaceKind
import io.embodit.fireplan.domain.model.Vec3

/**
 * Where a tap may put a marker (design `rules.placeMarker`):
 * - fire spot: a TABLE / CABINET top or the FLOOR;
 * - extinguisher: the FLOOR;
 * - exit: the FLOOR; a tap on a DOOR plane lands on the floor 0.3 m in front of the door;
 * - detector: the CEILING; without a ceiling plane a WALL is allowed (checked against the wall's top).
 * Anything else is refused ("can't place here").
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
    ): Outcome {
        val floorY = floorHeight(planes) ?: point.y
        return when (kind) {
            MarkerKind.FIRE ->
                when {
                    plane.kind == SurfaceKind.FLOOR && plane.horizontal -> Outcome.Place(Marker(id, kind, point, onTable = false))
                    isTopFace(plane) -> Outcome.Place(Marker(id, kind, point, onTable = true))
                    else -> Outcome.Refused
                }
            MarkerKind.EXTINGUISHER ->
                if (plane.kind == SurfaceKind.FLOOR && plane.horizontal) Outcome.Place(Marker(id, kind, point)) else Outcome.Refused
            MarkerKind.EXIT ->
                when {
                    plane.kind == SurfaceKind.FLOOR && plane.horizontal -> Outcome.Place(Marker(id, kind, point))
                    plane.kind == SurfaceKind.DOOR -> {
                        // the foot of the door, 0.3 m into the room (towards the head)
                        val n = plane.normal
                        var flat = Vec3(n.x, 0f, n.z)
                        if (flat.length < 1e-3f) return Outcome.Refused
                        flat = flat.normalized()
                        if ((head - plane.centroid).dot(flat) < 0f) flat = -flat
                        val c = plane.centroid
                        val foot = Vec3(c.x, floorY, c.z) + flat * DOOR_OFFSET_M
                        Outcome.Place(Marker(id, kind, foot))
                    }
                    else -> Outcome.Refused
                }
            MarkerKind.DETECTOR ->
                when {
                    plane.kind == SurfaceKind.CEILING ->
                        Outcome.Place(Marker(id, kind, point, onCeiling = true, wallDistM = detectorCheck.wallDistance(point, planes)))
                    plane.kind == SurfaceKind.WALL && !hasCeiling(planes) -> {
                        val below = (plane.maxY - point.y).coerceAtLeast(0f)
                        Outcome.Place(Marker(id, kind, point, onCeiling = false, belowCeilingM = below, wallDistM = detectorCheck.wallDistance(point, planes)))
                    }
                    else -> Outcome.Refused
                }
        }
    }

    /** A TABLE / CABINET plane whose normal points up: its top face. */
    private fun isTopFace(plane: PlaneSurface): Boolean =
        (plane.kind == SurfaceKind.TABLE || plane.kind == SurfaceKind.CABINET) && plane.horizontal

    companion object {
        const val DOOR_OFFSET_M = 0.3f

        fun hasCeiling(planes: Collection<PlaneSurface>): Boolean = planes.any { it.kind == SurfaceKind.CEILING }

        /** Height of the floor: the lowest FLOOR plane, or null when none is known yet. */
        fun floorHeight(planes: Collection<PlaneSurface>): Float? =
            planes.filter { it.kind == SurfaceKind.FLOOR && it.vertices.isNotEmpty() }.minOfOrNull { it.centroid.y }
    }
}
