package io.embodit.fireplan.ui.fire

import io.embodit.fireplan.domain.model.LanguagePref
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.Scenario
import io.embodit.fireplan.domain.model.ScenarioPref
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.domain.usecase.Finding

/** Base states of the design package (S00 - S23), as the app's mode; the drill phases refine DRILL. */
enum class Mode {
    STARTING,
    SCANNING,
    RESTORING,
    HOME,
    PLACING,
    ROUTE_PICK,
    ROUTE_RECORDING,
    ROUTE_DONE,
    DRILL,
    RESULT,
}

enum class DialogKind { CLEAR, NOT_HERE, UNAVAILABLE }

enum class DrillPhase { COUNTDOWN, FIRE, GOT, AIM, SWEEP, OUT, EVACUATE }

enum class AimTarget { NONE, RING, TOP }

enum class DistanceBad { NEAR, FAR }

enum class RoutePhase { TO_FIRE, TO_EXIT, DONE, SHORT }

/** The one line on the drill card. */
enum class HintKind {
    NONE,
    COUNTDOWN_SAFETY,
    LEAVE_DOOR,
    FIRE_START,
    GET_EXT,
    BACK_PULL,
    AIM,
    AIM_LOW,
    SWEEP,
    TOO_CLOSE,
    TOO_FAR,
    OUT,
    EMPTY,
    TOO_BIG,
    DONT_FIGHT,
}

/** A snackbar with this duration stays until its state goes away. */
const val SNACK_STICKY_MS = -1L

enum class SnackKind(val durationMs: Long) {
    SCANNING(SNACK_STICKY_MS),
    RESTORING(SNACK_STICKY_MS),
    RESTORED(2600L),
    PARTIAL(4000L),
    BAD_SURFACE(2600L),
    UNSAVED(8000L),
    LOST(SNACK_STICKY_MS),
    MAX(2600L),
}

data class SnackUi(val id: Int, val kind: SnackKind, val a: Int = 0, val b: Int = 0)

data class MarkerUi(
    val id: String,
    val kind: MarkerKind,
    val position: Vec3,
    val onTable: Boolean,
    val onCeiling: Boolean,
    /** 1 or 2 for exits when there are two of them, else 0. */
    val exitIndex: Int,
    /** Extinguisher / exit: horizontal distance to the fire spot, metres. */
    val fromFireM: Float?,
    val saved: Boolean,
)

data class DetectorCheckUi(
    val onCeiling: Boolean,
    val wallDistM: Float?,
    val belowCeilingM: Float?,
    val stoveDistM: Float?,
    val wallOk: Boolean,
    val topOk: Boolean,
    val stoveOk: Boolean,
)

data class RouteRecUi(val exitId: String, val phase: RoutePhase, val toFireM: Float, val walkedM: Float, val points: List<Vec3>)

data class DrillUi(
    val phase: DrillPhase,
    val scenario: Scenario,
    val elapsedS: Float,
    /** Seconds left on the countdown card (3, 2, 1). */
    val countdown: Int,
    /** Flame level 1 - 3. */
    val level: Int,
    val sprayLeftS: Float,
    val spraying: Boolean,
    val sweeps: Int,
    val distanceBad: DistanceBad?,
    val aim: AimTarget,
    val blockedAtDoor: Boolean,
    val paused: Boolean,
    val empty: Boolean,
    val dontFight: Boolean,
    val hint: HintKind,
    /** The flame has been put out (S16). */
    val fireOut: Boolean,
    /** Marker the alarm sounds from (nearest detector), or null for the fire itself. */
    val alarmMarkerId: String?,
)

data class ResultSegment(val kind: Int, val seconds: Float)

data class ResultUi(
    val totalS: Float,
    val scenario: Scenario,
    val segments: List<ResultSegment>,
    val findings: List<Finding>,
    val detectorsToMove: Int,
    val prevS: Float?,
    val trail: List<Vec3>,
)

data class FireUiState(
    val mode: Mode = Mode.STARTING,
    val markers: List<MarkerUi> = emptyList(),
    /** exit id -> route points (Stage metres, on the floor). */
    val routes: Map<String, List<Vec3>> = emptyMap(),
    val placeKind: MarkerKind? = null,
    val selectedId: String? = null,
    val detector: DetectorCheckUi? = null,
    val routeRec: RouteRecUi? = null,
    val drill: DrillUi? = null,
    val result: ResultUi? = null,
    val lastTotalS: Float? = null,
    val menuOpen: Boolean = false,
    val dialog: DialogKind? = null,
    val snack: SnackUi? = null,
    val coach: Boolean = false,
    val trackingLost: Boolean = false,
    val language: LanguagePref = LanguagePref.SYSTEM,
    val hints: Boolean = true,
    val scenarioPref: ScenarioPref = ScenarioPref.RANDOM,
    /** While restoring: markers found so far / stored. */
    val restoring: Pair<Int, Int>? = null,
    val floorY: Float = 0f,
    /** Outline of the floor plane (for the top-down map), Stage metres. */
    val floorOutline: List<Vec3> = emptyList(),
) {
    val placedCount: Int
        get() = markers.size

    fun count(kind: MarkerKind): Int = markers.count { it.kind == kind }

    fun full(kind: MarkerKind): Boolean = count(kind) >= kind.limit

    val exitCount: Int
        get() = count(MarkerKind.EXIT)

    val routeCount: Int
        get() = routes.size

    val canDrill: Boolean
        get() = count(MarkerKind.FIRE) > 0 && exitCount > 0

    val hasRecord: Boolean
        get() = lastTotalS != null

    val starting: Boolean
        get() = mode == Mode.STARTING || mode == Mode.SCANNING || mode == Mode.RESTORING

    val selectedMarker: MarkerUi?
        get() = selectedId?.let { id -> markers.firstOrNull { it.id == id } }

    /** The design's state name (S00 - S23 with +M1 / +M2), for the log and the debug `dump`. */
    val stateName: String
        get() {
            val d = drill
            val base =
                when {
                    dialog == DialogKind.CLEAR -> "S21"
                    dialog == DialogKind.NOT_HERE -> "S10"
                    dialog == DialogKind.UNAVAILABLE -> "S22"
                    menuOpen -> "S20"
                    d != null && d.paused -> "S19"
                    else -> baseName
                }
            val m1 = if (d != null && d.phase == DrillPhase.SWEEP && d.distanceBad != null && !d.paused) "+M1" else ""
            val m2 = if (trackingLost) "+M2" else ""
            return base + m1 + m2
        }

    private val baseName: String
        get() {
            val d = drill
            return when (mode) {
                    Mode.STARTING -> "S00"
                    Mode.SCANNING -> "S01"
                    Mode.RESTORING -> "S09"
                    Mode.HOME -> if (canDrill) "S03" else "S02"
                    Mode.PLACING -> if (coach) "S23" else if (selectedId != null) "S05" else "S04"
                    Mode.ROUTE_PICK -> "S06"
                    Mode.ROUTE_RECORDING -> "S07"
                    Mode.ROUTE_DONE -> "S08"
                    Mode.RESULT -> "S18"
                    Mode.DRILL ->
                        when (d?.phase) {
                            DrillPhase.COUNTDOWN -> "S11"
                            DrillPhase.FIRE -> "S12"
                            DrillPhase.GOT -> "S13"
                            DrillPhase.AIM -> "S14"
                            DrillPhase.SWEEP -> "S15"
                            DrillPhase.OUT -> "S16"
                            DrillPhase.EVACUATE -> "S17"
                            null -> "S11"
                        }
            }
        }
}

/** Everything that can happen: panel controls, scene callbacks, lifecycle and the debug hooks. */
sealed interface FireEvent {
    // scene
    data object SceneReady : FireEvent

    data class PlanesChanged(val count: Int, val hasFloor: Boolean, val floorY: Float, val hasCeiling: Boolean, val floorOutline: List<Vec3>) : FireEvent

    data class TrackingChanged(val lost: Boolean) : FireEvent

    /** Head position and viewing direction, ten times a second. */
    data class HeadSample(val position: Vec3, val forward: Vec3) : FireEvent

    data class SurfaceTapped(val planeId: String, val point: Vec3) : FireEvent

    data class MarkerTapped(val id: String) : FireEvent

    data object NothingTapped : FireEvent

    data object PinRingTapped : FireEvent

    data class AimChanged(val target: AimTarget) : FireEvent

    data class SprayStarted(val onRing: Boolean) : FireEvent

    data class SprayMoved(val lateralM: Float) : FireEvent

    data object SprayEnded : FireEvent

    // home / tray / route
    data object SetupClicked : FireEvent

    data object DrillClicked : FireEvent

    data object ResultClicked : FireEvent

    data object MoreClicked : FireEvent

    data object BackClicked : FireEvent

    data class TileClicked(val kind: MarkerKind) : FireEvent

    data object RouteClicked : FireEvent

    data class ExitPicked(val id: String) : FireEvent

    data object RouteStopClicked : FireEvent

    data object RouteRedoClicked : FireEvent

    data object RouteDoneClicked : FireEvent

    data object DeleteMarkerClicked : FireEvent

    data object CloseStripClicked : FireEvent

    // drill
    data object PauseClicked : FireEvent

    data object ResumeClicked : FireEvent

    data object RestartClicked : FireEvent

    data object EndDrillClicked : FireEvent

    data object PullPinClicked : FireEvent

    data object AgainClicked : FireEvent

    data object ResultDoneClicked : FireEvent

    // menu, dialogs, overlays
    data class LanguageSelected(val language: LanguagePref) : FireEvent

    data class HintsSelected(val on: Boolean) : FireEvent

    data class ScenarioSelected(val scenario: ScenarioPref) : FireEvent

    data object ClearAllClicked : FireEvent

    data object DialogDismissed : FireEvent

    data object ClearConfirmed : FireEvent

    data object NotHereRetry : FireEvent

    data object NotHereRedo : FireEvent

    data object ExitClicked : FireEvent

    data object CoachDismissed : FireEvent

    data class SnackActionClicked(val id: Int) : FireEvent

    data class SnackClosed(val id: Int) : FireEvent

    // lifecycle
    data object WentBackground : FireEvent

    data object CameForeground : FireEvent

    // debug hooks (debuggable builds only)
    data object DebugSeed : FireEvent

    data class DebugState(val name: String) : FireEvent

    data class DebugPlace(val kind: MarkerKind, val point: Vec3?) : FireEvent

    data class DebugDrill(val scenario: Scenario) : FireEvent

    data class DebugArrive(val target: String) : FireEvent

    data object DebugPass : FireEvent

    data class DebugSweep(val count: Int) : FireEvent

    data class DebugDistance(val which: String) : FireEvent

    data class DebugAim(val target: AimTarget) : FireEvent

    data class DebugSpray(val on: Boolean) : FireEvent

    data object DebugCoachReset : FireEvent

    data object DebugRestoreTimeout : FireEvent

    data class DebugTracking(val lost: Boolean?) : FireEvent

    data object DebugClear : FireEvent
}

/** One-off effects the screen plays (sound, closing the Stage). */
sealed interface FireUiEffect {
    data object ExitApp : FireUiEffect

    data class Sound(val kind: SoundKind) : FireUiEffect
}

enum class SoundKind { PLACE, DELETE, ARRIVE, PIN, OUT, FAIL, DONE }
