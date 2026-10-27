package io.embodit.fireplan.domain.model

/** The four kinds of marker, with the v1 limit per kind and the short tag used in ids, logs and debug commands. */
enum class MarkerKind(val limit: Int, val tag: String) {
    FIRE(1, "fire"),
    EXTINGUISHER(3, "ext"),
    EXIT(2, "exit"),
    DETECTOR(6, "det");

    companion object {
        fun fromTag(tag: String?): MarkerKind? = entries.firstOrNull { it.tag == tag || it.name.equals(tag, ignoreCase = true) }
    }
}

/**
 * A marker in the room. Its place is its world anchor plus [offset] (normally zero); [position] is the
 * current Stage position. A marker whose anchor could not be created is kept ([anchorId] null,
 * [saved] false) and shown with the "not saved" snackbar.
 */
data class Marker(
    val id: String,
    val kind: MarkerKind,
    val position: Vec3,
    val anchorId: String? = null,
    val offset: Vec3 = Vec3.ZERO,
    /** Fire spot on a table / cabinet top (no pole, plate 0.25 m above). */
    val onTable: Boolean = false,
    /** Detector on the ceiling (else wall-mounted fallback). */
    val onCeiling: Boolean = true,
    /** Wall-mounted detector: how far below the ceiling / wall top, metres. */
    val belowCeilingM: Float? = null,
    /** Detector: horizontal distance to the nearest wall, metres (null when no wall is known). */
    val wallDistM: Float? = null,
    val saved: Boolean = true,
    /** Restored from storage but its anchor was not found in this space: hidden until it is. */
    val found: Boolean = true,
)

/** Recorded escape route to one exit: floor points, Stage metres, 0.4 m apart. */
data class Route(val exitId: String, val points: List<Vec3>) {
    val lengthM: Float
        get() {
            var s = 0f
            for (i in 1 until points.size) s += points[i].distanceTo(points[i - 1])
            return s
        }
}

/** The scenario chosen in the menu. */
enum class ScenarioPref(val tag: String) {
    RANDOM("random"),
    SMALL("small"),
    BIG("big"),
}

/** The scenario a drill actually ran. */
enum class Scenario(val tag: String) {
    SMALL("small"),
    BIG("big");

    companion object {
        fun fromTag(tag: String?): Scenario = if (tag == "big") BIG else SMALL
    }
}

enum class LanguagePref(val tag: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en");

    companion object {
        fun fromTag(tag: String?): LanguagePref = entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}

data class Settings(
    val language: LanguagePref = LanguagePref.SYSTEM,
    val hints: Boolean = true,
    val scenario: ScenarioPref = ScenarioPref.RANDOM,
    val coachSeen: Boolean = false,
    /** v0.1.1: the room box size, metres (taste profile D7). */
    val roomW: Float = RoomBox.DEFAULT_W,
    val roomL: Float = RoomBox.DEFAULT_L,
    val roomH: Float = RoomBox.DEFAULT_H,
)

/** One finished drill (the last five are kept). */
data class DrillRecord(val atMs: Long, val scenario: Scenario, val totalS: Float)

/** A route as stored: its points in the frame of the exit's anchor. */
data class StoredRoute(val exitId: String, val pointsInAnchor: List<Vec3>)

/** What the app remembers between launches. Marker positions are not stored: they come from the anchors. */
data class StoredSession(
    val markers: List<Marker>,
    val routes: List<StoredRoute>,
    val records: List<DrillRecord>,
    val settings: Settings,
)

/** Why spatial features cannot be used (the head tracker's support state). */
enum class BlockReason {
    NOT_IN_FULL_SPACE,
    WITHOUT_PERMISSION,
    DEVICE_NOT_SUPPORTED,
}

/** The drill rules of the design package, in one place. */
object DrillRules {
    /** Head foot point within this of a marker = arrived. */
    const val ARRIVE_M = 0.8f

    /** During a drill the arrival must come from outside this radius. */
    const val LEAVE_M = 1.0f
    const val ARRIVE_HOLD_MS = 300L

    /** Route and trail points are this far apart. */
    const val ROUTE_STEP_M = 0.4f
    const val ROUTE_MIN_M = 1.5f
    const val ROUTE_MIN_POINTS = 4

    /** The fire grows one level after this long without spraying. */
    const val LEVEL_UP_S = 25f
    const val SPRAY_S = 12f
    const val SWEEPS_NEEDED = 3
    const val NEAR_M = 1.5f
    const val FAR_M = 3.5f

    /** The countdown waits while the user stands this close to an exit. */
    const val DOOR_CLEAR_M = 1.5f
    const val COUNTDOWN_S = 3f
    const val TWO_MINUTES_S = 120f

    /** The alarm drops to this volume once the extinguisher is in hand or the evacuation has started. */
    const val ALARM_LOW_VOLUME = 0.4f

    /** The first-hint line ("fire! fight it or get out?") stays this long before "get the extinguisher". */
    const val FIRST_HINT_S = 5f

    /** Flame sizes per level: height, width (metres). L1 is the only level that can be fought. */
    val FLAME_HEIGHT = floatArrayOf(0.30f, 0.60f, 1.10f)
    val FLAME_WIDTH = floatArrayOf(0.22f, 0.40f, 0.70f)

    /** Radius of the base ring = width x 0.6. */
    fun ringRadius(level: Int): Float = FLAME_WIDTH[(level - 1).coerceIn(0, 2)] * 0.6f

    /** Detector check limits (GB 50116-2013 / NFPA 72). */
    const val DETECTOR_WALL_M = 0.5f
    const val DETECTOR_TOP_M = 0.3f
    const val DETECTOR_STOVE_M = 3f

    /** Extinguisher findings: longer than this to fetch, or farther than this from the fire. */
    const val GET_SLOW_S = 15f
    const val EXT_FAR_M = 5f
}
