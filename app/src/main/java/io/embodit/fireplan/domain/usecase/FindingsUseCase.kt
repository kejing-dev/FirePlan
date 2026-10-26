package io.embodit.fireplan.domain.usecase

import io.embodit.fireplan.domain.model.DrillRules

/** The findings of the result panel (design `rules.findings`), in their fixed order; OK when none applies. */
enum class Finding {
    F1_TOO_BIG_TO_FIGHT,
    F2_OVER_TWO_MINUTES,
    F3_SECOND_EXTINGUISHER,
    F4_SECOND_EXIT,
    F5_NO_DETECTOR,
    F6_DETECTORS_TO_MOVE,
    F7_DISTANCE,
    OK,
}

/** What a finished drill is judged on. */
data class DrillSummary(
    val totalS: Float,
    /** Seconds until the extinguisher was reached; null when it never was. */
    val gotAtS: Float?,
    /** The extinguisher was fetched when the fire was already L2 or bigger. */
    val fetchedWhenTooBig: Boolean,
    /** Horizontal distance from the nearest extinguisher to the fire spot, metres; null without extinguishers. */
    val nearestExtinguisherM: Float?,
    val exitCount: Int,
    val detectorCount: Int,
    val detectorsToMove: Int,
    val wrongDistanceCount: Int,
)

class FindingsUseCase {
    fun findings(s: DrillSummary): List<Finding> {
        val out = ArrayList<Finding>()
        if (s.fetchedWhenTooBig) out += Finding.F1_TOO_BIG_TO_FIGHT
        if (s.totalS > DrillRules.TWO_MINUTES_S) out += Finding.F2_OVER_TWO_MINUTES
        if ((s.gotAtS != null && s.gotAtS > DrillRules.GET_SLOW_S) || (s.nearestExtinguisherM != null && s.nearestExtinguisherM > DrillRules.EXT_FAR_M)) {
            out += Finding.F3_SECOND_EXTINGUISHER
        }
        if (s.exitCount == 1) out += Finding.F4_SECOND_EXIT
        if (s.detectorCount == 0) out += Finding.F5_NO_DETECTOR
        if (s.detectorsToMove > 0) out += Finding.F6_DETECTORS_TO_MOVE
        if (s.wrongDistanceCount >= 2) out += Finding.F7_DISTANCE
        val top = out.take(MAX)
        return if (top.isEmpty()) listOf(Finding.OK) else top
    }

    companion object {
        const val MAX = 4
    }
}
