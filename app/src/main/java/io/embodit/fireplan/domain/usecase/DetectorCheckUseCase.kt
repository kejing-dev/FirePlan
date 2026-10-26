package io.embodit.fireplan.domain.usecase

import io.embodit.fireplan.domain.model.DrillRules
import io.embodit.fireplan.domain.model.Marker
import io.embodit.fireplan.domain.model.PlaneSurface
import io.embodit.fireplan.domain.model.SurfaceKind
import io.embodit.fireplan.domain.model.Vec3
import kotlin.math.abs
import kotlin.math.hypot

/** The three rows of the detector check card. A null value means "not known" and counts as a pass. */
data class DetectorCheck(
    val onCeiling: Boolean,
    val wallDistM: Float?,
    val belowCeilingM: Float?,
    val stoveDistM: Float?,
) {
    val wallOk: Boolean
        get() = wallDistM == null || wallDistM >= DrillRules.DETECTOR_WALL_M

    val topOk: Boolean
        get() = belowCeilingM == null || belowCeilingM <= DrillRules.DETECTOR_TOP_M

    val stoveOk: Boolean
        get() = stoveDistM == null || stoveDistM >= DrillRules.DETECTOR_STOVE_M

    /** One of the three rows shows a warning. */
    val anyFail: Boolean
        get() = !onCeiling || !wallOk || !topOk || !stoveOk
}

/**
 * Detector placement rules (design `rules.detectorCheck`): on the ceiling, at least 0.5 m from the
 * nearest wall (GB 50116-2013) and at least 3 m from the stove (NFPA 72). A wall-mounted detector
 * (fallback when no ceiling plane is known) must sit within 0.3 m of the ceiling.
 */
class DetectorCheckUseCase {

    fun check(detector: Marker, fire: Marker?, planes: Collection<PlaneSurface>): DetectorCheck =
        DetectorCheck(
            onCeiling = detector.onCeiling,
            wallDistM = detector.wallDistM ?: wallDistance(detector.position, planes),
            belowCeilingM = if (detector.onCeiling) null else detector.belowCeilingM,
            stoveDistM = fire?.let { horizontal(detector.position, it.position) },
        )

    /** Horizontal distance from [point] to the nearest wall plane whose extent covers the point, or null. */
    fun wallDistance(point: Vec3, planes: Collection<PlaneSurface>): Float? {
        var best: Float? = null
        for (plane in planes) {
            if (plane.kind != SurfaceKind.WALL || plane.vertices.isEmpty()) continue
            val n = plane.normal
            // only walls that are roughly vertical
            if (abs(n.y) > 0.5f) continue
            val flat = Vec3(n.x, 0f, n.z).normalized()
            val d = abs((point - plane.vertices[0]).dot(flat))
            // the point should lie within the wall's horizontal span (grown by 0.5 m)
            if (!withinSpan(plane, point, flat)) continue
            if (best == null || d < best) best = d
        }
        return best
    }

    private fun withinSpan(plane: PlaneSurface, point: Vec3, flatNormal: Vec3): Boolean {
        val along = Vec3(-flatNormal.z, 0f, flatNormal.x)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in plane.vertices) {
            val a = v.dot(along)
            if (a < lo) lo = a
            if (a > hi) hi = a
        }
        val p = point.dot(along)
        return p >= lo - SPAN_GROW_M && p <= hi + SPAN_GROW_M
    }

    companion object {
        private const val SPAN_GROW_M = 0.5f

        fun horizontal(a: Vec3, b: Vec3): Float = hypot(a.x - b.x, a.z - b.z)
    }
}
