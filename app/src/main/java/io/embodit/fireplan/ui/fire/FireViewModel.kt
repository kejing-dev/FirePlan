package io.embodit.fireplan.ui.fire

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.embodit.fireplan.data.AnchorOutcome
import io.embodit.fireplan.data.AnchorPose
import io.embodit.fireplan.domain.model.DrillRecord
import io.embodit.fireplan.domain.model.DrillRules
import io.embodit.fireplan.domain.model.LanguagePref
import io.embodit.fireplan.domain.model.Marker
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.PlaneSurface
import io.embodit.fireplan.domain.model.Quat4
import io.embodit.fireplan.domain.model.RoomBox
import io.embodit.fireplan.domain.model.RoomSource
import io.embodit.fireplan.domain.model.Route
import io.embodit.fireplan.domain.model.Scenario
import io.embodit.fireplan.domain.model.ScenarioPref
import io.embodit.fireplan.domain.model.Settings
import io.embodit.fireplan.domain.model.StoredRoute
import io.embodit.fireplan.domain.model.StoredSession
import io.embodit.fireplan.domain.model.SurfaceKind
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.domain.usecase.DetectorCheckUseCase
import io.embodit.fireplan.domain.usecase.DrillSummary
import io.embodit.fireplan.domain.usecase.FindingsUseCase
import io.embodit.fireplan.domain.usecase.HitSurfaceUseCase
import io.embodit.fireplan.domain.usecase.PlaceMarkerUseCase
import io.embodit.fireplan.platform.FireGraph
import io.embodit.fireplan.platform.FireLog
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The whole state machine of the app (design `states`, `rules`). Events come from the panels, the
 * scene (taps, head samples ten times a second, planes, tracking) and the debug hooks; the state goes
 * out as [FireUiState]. The drill clock ticks here every 100 ms. Main thread only.
 */
class FireViewModel(private val appContext: Context) : ViewModel() {

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FireViewModel(context.applicationContext) as T
    }

    private val repository = FireGraph.repository(appContext)
    private val anchors = FireGraph.anchors
    private val planeTracker = FireGraph.planes
    private val placeRule = PlaceMarkerUseCase()
    private val detectorRule = DetectorCheckUseCase()
    private val findingsRule = FindingsUseCase()
    private val hits = HitSurfaceUseCase()

    private val _state = MutableStateFlow(FireUiState())
    val state: StateFlow<FireUiState> = _state.asStateFlow()
    private val _effects = MutableSharedFlow<FireUiEffect>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val effects = _effects.asSharedFlow()

    // ---- the model
    private val markers = ArrayList<Marker>()
    private val routes = LinkedHashMap<String, Route>()
    private val records = ArrayList<DrillRecord>()
    private var settings = Settings()
    private var storedRoutes: List<StoredRoute> = emptyList()
    private val anchorPoses = HashMap<String, AnchorPose>()
    private var loaded = false
    private var uid = 1

    // ---- session facts
    private var mode = Mode.STARTING
    private var forcedMode: Mode? = null
    private var sceneReady = false
    private var floorY = 0f
    private var floorOutline: List<Vec3> = emptyList()

    // ---- the room (v0.1.1, taste profile D7): plane detection gives at most the floor / ceiling height
    private var room: RoomBox? = null
    private var roomSource: RoomSource? = null
    private var roomEdit = false
    private var listOpen = false
    private var lastMoveMs = 0L
    private var realFloorY: Float? = null
    private var realCeilingY: Float? = null
    /** The user changed the height: a ceiling detected later does not override it. */
    private var heightTouched = false
    private var scanJob: Job? = null
    private val hasFloor: Boolean
        get() = room != null
    private var placeKind: MarkerKind? = null
    private var selectedId: String? = null
    private var menuOpen = false
    private var dialog: DialogKind? = null
    private var snack: SnackUi? = null
    private var snackSerial = 0
    private var snackJob: Job? = null
    private var coachVisible = false
    private var trackingLost = false
    private var restoreJob: Job? = null
    private var restoring: Pair<Int, Int>? = null
    private var blockedSinceMs = NEVER
    private var lastResult: ResultUi? = null
    private var lastStateName = ""
    private var markersUi: List<MarkerUi> = emptyList()
    private var routesUi: Map<String, List<Vec3>> = emptyMap()
    private var markersDirty = true
    private var routesDirty = true

    // ---- head
    private var head: Vec3? = null
    private var headForward = Vec3.AHEAD
    private val nearSince = HashMap<String, Long>()
    private val wasFar = HashMap<String, Boolean>()

    // ---- route recording
    private class RouteRec(val exitId: String) {
        var phase = RoutePhase.TO_FIRE
        val raw = ArrayDeque<Vec3>()
        val points = ArrayList<Vec3>()
        var length = 0f
        var toFire = 0f
    }

    private var routeRec: RouteRec? = null

    // ---- drill
    private class Drill(val scenario: Scenario) {
        var phase = DrillPhase.COUNTDOWN
        var countdown = DrillRules.COUNTDOWN_S
        var elapsed = 0f
        var level = 1
        var grow = 0f
        var sprayLeft = DrillRules.SPRAY_S
        var spraying = false
        var sweeps = 0
        var side = 0
        var wrongDist = 0
        var distanceBad: DistanceBad? = null
        var aim = AimTarget.NONE
        var blockedAtDoor = false
        var paused = false
        var empty = false
        var dontFight = false
        var f1 = false
        var fireOut = false
        var gotAt: Float? = null
        var outAt: Float? = null
        var evacAt: Float? = null
        val trail = ArrayList<Vec3>()
        var alarmMarkerId: String? = null
        var distanceOverride: String? = null
        var holdCountdown = false
    }

    private var drill: Drill? = null
    private var tickJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val s = runCatching { repository.load() }.getOrNull()
        if (s != null) {
            settings = s.settings
            records.clear()
            records += s.records
            markers.clear()
            markers += s.markers.map { it.copy(found = false) }
            storedRoutes = s.routes
            uid = (markers.mapNotNull { it.id.removePrefix("m").toIntOrNull() }.maxOrNull() ?: 0) + 1
        }
        loaded = true
        FireLog.i("LOAD markers=${markers.size} routes=${storedRoutes.size} records=${records.size} lang=${settings.language.tag}")
        settle()
        publish()
    }

    // =========================================================================================
    // events
    // =========================================================================================

    fun onEvent(event: FireEvent) {
        when (event) {
            is FireEvent.HeadSample -> {
                onHead(event.position, event.forward)
                return
            }
            is FireEvent.SprayMoved -> {
                onSprayMoved(event.lateralM)
                return
            }
            else -> Unit
        }
        if (event !is FireEvent.TrackingChanged && event !is FireEvent.PlanesChanged && event !is FireEvent.SceneReady) {
            FireLog.i("EVENT ${event::class.simpleName}")
        }
        when (event) {
            FireEvent.SceneReady -> {
                sceneReady = true
                startScanTimer()
                settle()
            }
            is FireEvent.PlanesChanged -> {
                realFloorY = event.realFloorY
                realCeilingY = event.realCeilingY
                adoptScan()
                settle()
            }
            is FireEvent.TrackingChanged -> {
                trackingLost = event.lost
                if (trackingLost) showSnack(SnackKind.LOST) else clearSnack(SnackKind.LOST)
                FireLog.i("TRACK ${if (trackingLost) "lost" else "recovered"}")
            }
            is FireEvent.SurfaceTapped -> onSurfaceTapped(event.planeId, event.point)
            is FireEvent.MarkerTapped -> onMarkerTapped(event.id)
            FireEvent.NothingTapped -> onNothingTapped()
            is FireEvent.OutsideTapped -> onOutsideTapped(event.point)
            FireEvent.PinRingTapped, FireEvent.PullPinClicked, FireEvent.DebugPass -> pullPin()
            is FireEvent.AimChanged -> onAim(event.target)
            is FireEvent.SprayStarted -> onSprayStarted(event.onRing)
            FireEvent.SprayEnded -> onSprayEnded()
            FireEvent.SetupClicked -> enterPlacing()
            FireEvent.DrillClicked, FireEvent.AgainClicked -> startDrill(null, skipCountdown = false)
            FireEvent.ResultClicked -> showLastResult()
            FireEvent.MoreClicked -> {
                menuOpen = true
                forcedMode = null
            }
            FireEvent.BackClicked -> onBack()
            is FireEvent.TileClicked -> if (!full(event.kind)) placeKind = event.kind
            FireEvent.RouteClicked -> onRouteClicked()
            is FireEvent.ExitPicked -> startRoute(event.id)
            FireEvent.RouteStopClicked -> {
                routeRec = null
                mode = Mode.PLACING
            }
            FireEvent.RouteRedoClicked -> routeRec?.let { startRoute(it.exitId) }
            FireEvent.RouteDoneClicked -> {
                routeRec = null
                mode = Mode.PLACING
            }
            FireEvent.RouteAutoClicked -> routeRec?.let { autoRoute(it) }
            FireEvent.RoomClicked ->
                if (room != null && (mode == Mode.HOME || mode == Mode.PLACING)) {
                    roomEdit = true
                    listOpen = false
                    menuOpen = false
                    selectedId = null
                    FireLog.i("ROOM edit ${room?.label}")
                }
            FireEvent.RoomDoneClicked -> {
                roomEdit = false
                save()
            }
            is FireEvent.RoomResized ->
                room?.let { r ->
                    val n = r.resized(event.dw, event.dl, event.dh)
                    if (event.dh != 0f) heightTouched = true
                    settings = settings.copy(roomW = n.width, roomL = n.length, roomH = n.height)
                    applyRoom(n)
                    FireLog.i("ROOM size ${n.label}")
                }
            is FireEvent.RoomMoved -> room?.let { r -> applyRoom(r.moved(headForward, event.right, event.ahead, event.turnDeg)) }
            FireEvent.MuteToggled -> {
                settings = settings.copy(muted = !settings.muted)
                save()
                FireLog.i("SOUND muted=${settings.muted}")
            }
            FireEvent.ListClicked ->
                if (mode == Mode.HOME || mode == Mode.PLACING) {
                    listOpen = true
                    roomEdit = false
                    menuOpen = false
                    selectedId = null
                    FireLog.i("LIST open n=${markers.count { it.found }}")
                }
            FireEvent.ListClosed -> listOpen = false
            is FireEvent.MarkerToFront -> moveToFront(event.id)
            is FireEvent.MarkerDeleted -> deleteMarker(event.id)
            is FireEvent.MarkerMoved -> planeTracker.get(event.planeId)?.let { moveMarkerTo(event.id, it, event.point) }
            FireEvent.RoomRecentered ->
                room?.let { r ->
                    applyRoom(RoomBox.around(head ?: Vec3(0f, 1.6f, 0f), headForward, r.width, r.length, r.height, r.floorY))
                    FireLog.i("ROOM recenter")
                }
            FireEvent.DeleteMarkerClicked -> selectedId?.let { deleteMarker(it) }
            FireEvent.CloseStripClicked -> selectedId = null
            FireEvent.PauseClicked -> drill?.let { if (!it.paused) { it.paused = true; FireLog.i("DRILL pause") } }
            FireEvent.ResumeClicked -> drill?.let { it.paused = false }
            FireEvent.RestartClicked -> startDrill(null, skipCountdown = false)
            FireEvent.EndDrillClicked -> endDrill()
            FireEvent.ResultDoneClicked -> {
                mode = Mode.HOME
                forcedMode = null
            }
            is FireEvent.LanguageSelected -> {
                settings = settings.copy(language = event.language)
                save()
                FireLog.i("LANG ${event.language.tag}")
            }
            is FireEvent.HintsSelected -> {
                settings = settings.copy(hints = event.on)
                save()
            }
            is FireEvent.ScenarioSelected -> {
                settings = settings.copy(scenario = event.scenario)
                save()
            }
            FireEvent.ClearAllClicked -> {
                menuOpen = false
                dialog = DialogKind.CLEAR
            }
            FireEvent.DialogDismissed -> {
                if (dialog == DialogKind.CLEAR) menuOpen = true
                dialog = null
            }
            FireEvent.ClearConfirmed, FireEvent.NotHereRedo, FireEvent.DebugClear -> clearAll()
            FireEvent.NotHereRetry -> {
                dialog = null
                startRestore()
            }
            FireEvent.ExitClicked -> {
                menuOpen = false
                emit(FireUiEffect.ExitApp)
            }
            FireEvent.CoachDismissed -> {
                coachVisible = false
                settings = settings.copy(coachSeen = true)
                save()
                FireLog.i("COACH dismissed")
            }
            is FireEvent.SnackActionClicked -> {
                if (snack?.id == event.id && snack?.kind == SnackKind.UNSAVED) {
                    snack = null
                    markers.filter { it.found && !it.saved && it.anchorId == null }.forEach { createAnchor(it.id) }
                }
            }
            is FireEvent.SnackClosed -> if (snack?.id == event.id) snack = null
            FireEvent.WentBackground -> drill?.let { if (!it.paused && it.phase != DrillPhase.COUNTDOWN) { it.paused = true; FireLog.i("DRILL pause reason=background") } }
            FireEvent.CameForeground -> Unit
            FireEvent.DebugSeed -> seed()
            is FireEvent.DebugState -> debugState(event.name)
            is FireEvent.DebugPlace -> debugPlace(event.kind, event.point)
            is FireEvent.DebugDrill -> startDrill(event.scenario, skipCountdown = true)
            is FireEvent.DebugArrive -> debugArrive(event.target)
            is FireEvent.DebugSweep -> debugSweep(event.count)
            is FireEvent.DebugDistance -> drill?.let { it.distanceOverride = if (event.which == "ok") null else event.which }
            is FireEvent.DebugAim -> onAim(event.target)
            is FireEvent.DebugSpray -> if (event.on) onSprayStarted(true) else onSprayEnded()
            FireEvent.DebugCoachReset -> {
                settings = settings.copy(coachSeen = false)
                coachVisible = mode == Mode.PLACING
                FireLog.i("COACH reset")
            }
            FireEvent.DebugRestoreTimeout -> {
                restoreJob?.cancel()
                restoring = null
                clearSnack(SnackKind.RESTORING)
                if (mode == Mode.RESTORING) mode = Mode.HOME
                dialog = DialogKind.NOT_HERE
            }
            is FireEvent.DebugTracking -> FireGraph.head.forceLost = event.lost ?: false
            is FireEvent.DebugNoScan -> {
                planeTracker.ignoreDetected = event.on
                realFloorY = planeTracker.realFloorY
                realCeilingY = planeTracker.realCeilingY
                FireLog.i("ROOM noscan=${event.on}")
                if (event.on && drill == null) {
                    // as a cold start where nothing is detected: scanning again, the 5 s timeout makes the virtual room
                    room = null
                    roomSource = null
                    roomEdit = false
                    planeTracker.setRoom(null)
                    scanJob?.cancel()
                    scanJob = null
                    if (mode == Mode.HOME || mode == Mode.PLACING || mode == Mode.SCANNING) mode = Mode.SCANNING
                    showSnack(SnackKind.SCANNING)
                    startScanTimer()
                }
            }
            FireEvent.DebugScanTimeout -> {
                if (room == null) createRoom()
                settle()
            }
            is FireEvent.HeadSample, is FireEvent.SprayMoved -> Unit
        }
        publish()
    }

    // ---- start-up, scanning, restoring

    /** Taste profile D7: never stuck scanning. Without a detected floor after 5 s the virtual room is used. */
    private fun startScanTimer() {
        if (room != null || scanJob != null) return
        scanJob =
            viewModelScope.launch {
                delay(SCAN_TIMEOUT_MS)
                if (room == null) {
                    createRoom()
                    settle()
                    publish()
                }
            }
    }

    /** The room box around the user: on the detected floor if there is one, else on the Stage ground. */
    private fun createRoom() {
        val fy = realFloorY ?: 0f
        val height = realCeilingY?.let { (it - fy).coerceIn(RoomBox.MIN_H, RoomBox.MAX_H) } ?: settings.roomH
        val source = if (realFloorY != null) RoomSource.SCAN else RoomSource.VIRTUAL
        roomSource = source
        applyRoom(RoomBox.around(head ?: Vec3(0f, 1.6f, 0f), headForward, settings.roomW, settings.roomL, height, fy))
        scanJob?.cancel()
        clearSnack(SnackKind.SCANNING)
        showSnack(if (source == RoomSource.VIRTUAL) SnackKind.VIRTUAL_ROOM else SnackKind.ROOM_SCANNED)
        FireLog.i("ROOM create source=${source.name.lowercase(Locale.US)} ${room?.label} floorY=${FireLog.f(fy)} ceiling=${realCeilingY != null}")
    }

    /** A detected floor / ceiling moves the room's floor / ceiling (the user's own height wins over a later ceiling). */
    private fun adoptScan() {
        val r = room
        val rf = realFloorY
        if (r == null) {
            if (rf != null && sceneReady) createRoom()
            return
        }
        var next = r
        if (rf != null && abs(rf - r.floorY) > ADOPT_EPS_M) next = next.copy(floorY = rf, height = (r.ceilingY - rf).coerceIn(RoomBox.MIN_H, RoomBox.MAX_H))
        val rc = realCeilingY
        if (rc != null && !heightTouched) {
            val h = (rc - next.floorY).coerceIn(RoomBox.MIN_H, RoomBox.MAX_H)
            if (abs(h - next.height) > ADOPT_EPS_M) next = next.copy(height = h)
        }
        if (rf != null && roomSource == RoomSource.VIRTUAL) {
            roomSource = RoomSource.SCAN
            if (snack == null || snack?.kind == SnackKind.VIRTUAL_ROOM) showSnack(SnackKind.ROOM_SCANNED)
        }
        if (next != r) {
            applyRoom(next)
            FireLog.i("ROOM adopt floorY=${FireLog.f(next.floorY)} height=${FireLog.f(next.height)}")
        }
    }

    private fun applyRoom(r: RoomBox) {
        room = r
        floorY = r.floorY
        floorOutline = r.corners()
        planeTracker.setRoom(r)
        routesDirty = true
    }

    private fun settle() {
        forcedMode?.let {
            mode = it
            return
        }
        if (!sceneReady || !loaded) {
            mode = Mode.STARTING
            return
        }
        if (mode == Mode.RESTORING) return
        if (mode == Mode.STARTING && markers.any { !it.found && it.anchorId != null }) {
            startRestore()
            return
        }
        if (!hasFloor && (mode == Mode.STARTING || mode == Mode.SCANNING)) {
            mode = Mode.SCANNING
            if (snack?.kind != SnackKind.SCANNING) showSnack(SnackKind.SCANNING)
            return
        }
        if (mode == Mode.STARTING || mode == Mode.SCANNING) {
            clearSnack(SnackKind.SCANNING)
            mode = Mode.HOME
        }
    }

    private fun startRestore() {
        val stored = markers.count { it.anchorId != null && !it.found }
        if (stored == 0) {
            mode = if (hasFloor) Mode.HOME else Mode.SCANNING
            return
        }
        mode = Mode.RESTORING
        restoring = 0 to stored
        showSnack(SnackKind.RESTORING)
        restoreJob?.cancel()
        restoreJob =
            viewModelScope.launch {
                var found = tryRestore()
                val t0 = SystemClock.elapsedRealtime()
                while (found == 0 && SystemClock.elapsedRealtime() - t0 < RESTORE_TIMEOUT_MS && isActive) {
                    delay(RESTORE_RETRY_MS)
                    found = tryRestore()
                }
                restoring = null
                clearSnack(SnackKind.RESTORING)
                if (found == 0) {
                    mode = Mode.HOME
                    dialog = DialogKind.NOT_HERE
                    FireLog.i("RESTORE failed stored=$stored")
                } else {
                    mode = if (hasFloor) Mode.HOME else Mode.SCANNING
                    if (mode == Mode.SCANNING) showSnack(SnackKind.SCANNING)
                    else if (found == stored) showSnack(SnackKind.RESTORED, found) else showSnack(SnackKind.PARTIAL, found, stored)
                    FireLog.i("RESTORE found=$found stored=$stored")
                }
                publish()
            }
    }

    /** Loads the anchors and places the stored markers (and the routes under their exits); returns how many were found. */
    private suspend fun tryRestore(): Int {
        val outcome = anchors.loadAll()
        val list = (outcome as? AnchorOutcome.Ok)?.value ?: return 0
        val byId = list.associateBy { it.id }
        var found = 0
        for (i in markers.indices) {
            val m = markers[i]
            if (m.found) {
                found++
                continue
            }
            val rec = m.anchorId?.let { byId[it] } ?: continue
            anchorPoses[rec.id] = rec.pose
            markers[i] = m.copy(position = rec.pose.position + rec.pose.rotation.rotate(m.offset), found = true)
            found++
            FireLog.i("ANCHOR load id=${m.id} uuid=${rec.id} pos=${FireLog.pos(markers[i].position)}")
        }
        for (r in storedRoutes) {
            if (routes.containsKey(r.exitId)) continue
            val exit = markers.firstOrNull { it.id == r.exitId && it.found } ?: continue
            val pose = exit.anchorId?.let { anchorPoses[it] } ?: continue
            routes[r.exitId] = Route(r.exitId, r.pointsInAnchor.map { pose.position + pose.rotation.rotate(it) })
            routesDirty = true
        }
        markersDirty = true
        restoring = found to (restoring?.second ?: markers.size)
        publish()
        return found
    }

    private fun checkBlocked() {
        if (!sceneReady) return
        val reason = FireGraph.head.blockReason()
        val now = SystemClock.elapsedRealtime()
        if (reason == null) {
            blockedSinceMs = NEVER
            return
        }
        if (blockedSinceMs == NEVER) blockedSinceMs = now
        if (now - blockedSinceMs > BLOCKED_GRACE_MS && dialog != DialogKind.UNAVAILABLE) {
            FireLog.i("BLOCKED reason=$reason")
            dialog = DialogKind.UNAVAILABLE
            publish()
        }
    }

    // ---- placing

    private fun enterPlacing() {
        if (mode == Mode.DRILL) return
        mode = Mode.PLACING
        forcedMode = null
        selectedId = null
        if (placeKind == null || full(placeKind!!)) placeKind = nextMissing()
        coachVisible = !settings.coachSeen
    }

    private fun onSurfaceTapped(planeId: String, point: Vec3) {
        if (menuOpen) {
            menuOpen = false
            return
        }
        if (dialog != null || roomEdit) return
        if (mode != Mode.PLACING) return
        if (selectedId != null) {
            selectedId = null
            return
        }
        val kind = placeKind ?: return
        if (full(kind)) {
            showSnack(SnackKind.MAX, kind.limit)
            return
        }
        val plane = planeTracker.get(planeId) ?: return
        placeOn(kind, plane, point)
    }

    /**
     * A tap outside the room box (the backstop shell): the line from the head through the tap point is
     * met with the room's floor (the ceiling for a detector), so that a room bigger than the box still works.
     */
    private fun onOutsideTapped(p: Vec3) {
        val kind = placeKind
        val h = head
        val r = room
        if (menuOpen || dialog != null || roomEdit || mode != Mode.PLACING || selectedId != null || kind == null || h == null || r == null) {
            onNothingTapped()
            return
        }
        if (full(kind)) {
            showSnack(SnackKind.MAX, kind.limit)
            return
        }
        val targetY = if (kind == MarkerKind.DETECTOR) r.ceilingY else r.floorY
        val d = p - h
        val t = if (abs(d.y) > 1e-4f) (targetY - h.y) / d.y else -1f
        val q = h + d * t
        val plane = planeTracker.get(if (kind == MarkerKind.DETECTOR) RoomBox.ID_CEILING else RoomBox.ID_FLOOR)
        if (t <= 0f || horizontal(q, h) > OUTSIDE_MAX_M || plane == null) {
            FireLog.i("MARKER refused kind=${kind.tag} outside t=${FireLog.f(t)}")
            showSnack(SnackKind.BAD_SURFACE)
            emit(FireUiEffect.Sound(SoundKind.FAIL))
            return
        }
        FireLog.i("TAP outside -> ${FireLog.pos(q)}")
        placeOn(kind, plane, q)
    }

    private fun placeOn(kind: MarkerKind, plane: PlaneSurface, point: Vec3) {
        val r = room
        when (val out = placeRule.place(newId(), kind, plane, point, head ?: point, planeTracker.all, r?.floorY ?: floorY, r?.ceilingY ?: (floorY + RoomBox.DEFAULT_H))) {
            is PlaceMarkerUseCase.Outcome.Place -> addMarker(out.marker, via = "tap")
            PlaceMarkerUseCase.Outcome.Refused -> {
                FireLog.i("MARKER refused kind=${kind.tag} plane=${plane.label}")
                showSnack(SnackKind.BAD_SURFACE)
                emit(FireUiEffect.Sound(SoundKind.FAIL))
            }
        }
    }

    private fun addMarker(m: Marker, via: String) {
        val kind = m.kind
        if (full(kind)) {
            showSnack(SnackKind.MAX, kind.limit)
            return
        }
        // an unfound marker of this kind gives way to the new one
        if (visibleCount(kind) + markers.count { !it.found && it.kind == kind } >= kind.limit) markers.removeAll { !it.found && it.kind == kind }
        markers += m.copy(found = true, saved = false)
        markersDirty = true
        FireLog.i("MARKER add id=${m.id} kind=${kind.tag} pos=${FireLog.pos(m.position)} via=$via onTable=${m.onTable} onCeiling=${m.onCeiling}")
        emit(FireUiEffect.Sound(SoundKind.PLACE))
        coachVisible = false
        if (kind == MarkerKind.DETECTOR) selectedId = m.id
        val missing = nextMissing()
        if (full(kind) || ((kind == MarkerKind.FIRE || kind == MarkerKind.EXIT) && visibleCount(missing) == 0)) placeKind = missing
        save()
        createAnchor(m.id)
    }

    private fun createAnchor(id: String) {
        val m = markers.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            when (val r = anchors.create(m.position, "fp_${m.kind.tag}_$id")) {
                is AnchorOutcome.Ok -> {
                    val i = markers.indexOfFirst { it.id == id }
                    if (i < 0) {
                        anchors.remove(r.value.id)
                        return@launch
                    }
                    anchorPoses[r.value.id] = r.value.pose
                    val cur = markers[i]
                    markers[i] = cur.copy(anchorId = r.value.id, offset = r.value.pose.rotation.conjugate().rotate(cur.position - r.value.pose.position), saved = true)
                    markersDirty = true
                    FireLog.i("ANCHOR saved id=$id uuid=${r.value.id}")
                    save()
                }
                is AnchorOutcome.Failed -> {
                    FireLog.w("ANCHOR failed id=$id code=${r.code} ${r.message}")
                    showSnack(SnackKind.UNSAVED)
                }
            }
            publish()
        }
    }

    /** The list's "move to front": 1.5 m ahead of the user, on the floor (a detector on the ceiling). */
    private fun moveToFront(id: String) {
        val m = markers.firstOrNull { it.id == id && it.found } ?: return
        val h = head ?: return
        val r = room ?: return
        val f = RoomBox.level(headForward)
        val target = h + f * FRONT_M
        val onCeiling = m.kind == MarkerKind.DETECTOR
        val plane = planeTracker.get(if (onCeiling) RoomBox.ID_CEILING else RoomBox.ID_FLOOR) ?: return
        moveMarkerTo(id, plane, Vec3(target.x, if (onCeiling) r.ceilingY else r.floorY, target.z))
    }

    /** Moves a placed marker to where the placement rules put [point] on [plane]; its anchor is made anew. */
    private fun moveMarkerTo(id: String, plane: PlaneSurface, point: Vec3) {
        val i = markers.indexOfFirst { it.id == id && it.found }
        if (i < 0 || mode == Mode.DRILL) return
        val old = markers[i]
        val r = room
        val out = placeRule.place(id, old.kind, plane, point, head ?: point, planeTracker.all, r?.floorY ?: floorY, r?.ceilingY ?: (floorY + RoomBox.DEFAULT_H))
        val placed = (out as? PlaceMarkerUseCase.Outcome.Place)?.marker ?: return
        old.anchorId?.let { uuid -> viewModelScope.launch { anchors.remove(uuid) } }
        markers[i] = old.copy(position = placed.position, onTable = placed.onTable, onCeiling = placed.onCeiling, belowCeilingM = placed.belowCeilingM, wallDistM = placed.wallDistM, anchorId = null, offset = Vec3.ZERO, saved = false)
        markersDirty = true
        // a route starts at the fire spot and ends at its exit: moving either makes it stale
        if (old.kind == MarkerKind.FIRE && routes.isNotEmpty()) {
            routes.clear()
            routesDirty = true
        } else if (old.kind == MarkerKind.EXIT && routes.remove(id) != null) {
            routesDirty = true
        }
        lastMoveMs = SystemClock.elapsedRealtime()
        FireLog.i("MARKER move id=$id kind=${old.kind.tag} pos=${FireLog.pos(placed.position)} plane=${plane.label}")
        emit(FireUiEffect.Sound(SoundKind.PLACE))
        save()
        createAnchor(id)
    }

    private fun deleteMarker(id: String) {
        val m = markers.firstOrNull { it.id == id } ?: return
        markers.remove(m)
        markersDirty = true
        if (m.kind == MarkerKind.EXIT && routes.remove(id) != null) routesDirty = true
        selectedId = null
        m.anchorId?.let { uuid -> viewModelScope.launch { anchors.remove(uuid) } }
        FireLog.i("MARKER remove id=$id kind=${m.kind.tag}")
        emit(FireUiEffect.Sound(SoundKind.DELETE))
        if (placeKind == null || full(placeKind!!)) placeKind = nextMissing()
        save()
    }

    private fun clearAll() {
        for (m in markers) m.anchorId?.let { uuid -> viewModelScope.launch { anchors.remove(uuid) } }
        markers.clear()
        routes.clear()
        storedRoutes = emptyList()
        records.clear()
        lastResult = null
        markersDirty = true
        routesDirty = true
        selectedId = null
        dialog = null
        menuOpen = false
        placeKind = MarkerKind.FIRE
        restoreJob?.cancel()
        restoring = null
        clearSnack(SnackKind.RESTORING)
        if (mode == Mode.RESTORING || mode == Mode.RESULT) mode = if (hasFloor) Mode.HOME else Mode.SCANNING
        FireLog.i("CLEAR all")
        save()
    }

    private fun onMarkerTapped(id: String) {
        if (menuOpen) {
            menuOpen = false
            return
        }
        // the system tap that ends a controller drag is not a selection
        if (SystemClock.elapsedRealtime() - lastMoveMs < MOVE_TAP_GRACE_MS) return
        if (mode != Mode.PLACING || dialog != null) return
        selectedId = if (selectedId == id) null else id
    }

    private fun onNothingTapped() {
        if (menuOpen) {
            menuOpen = false
            return
        }
        if (selectedId != null) selectedId = null
    }

    private fun onBack() {
        forcedMode = null
        if (roomEdit) {
            roomEdit = false
            save()
            return
        }
        if (listOpen) {
            listOpen = false
            return
        }
        when (mode) {
            Mode.PLACING -> {
                selectedId = null
                mode = Mode.HOME
            }
            Mode.ROUTE_PICK, Mode.ROUTE_RECORDING, Mode.ROUTE_DONE -> {
                routeRec = null
                mode = Mode.PLACING
            }
            else -> Unit
        }
    }

    // ---- routes

    private fun onRouteClicked() {
        val exits = visible(MarkerKind.EXIT)
        if (fireMarker() == null || exits.isEmpty()) return
        if (exits.size >= 2) mode = Mode.ROUTE_PICK else startRoute(exits[0].id)
    }

    private fun startRoute(exitId: String) {
        val fire = fireMarker() ?: return
        if (markers.none { it.id == exitId && it.found }) return
        val rec = RouteRec(exitId)
        val h = head
        if (h != null && horizontal(h, fire.position) <= DrillRules.ARRIVE_M) {
            rec.phase = RoutePhase.TO_EXIT
            rec.points += foot(h)
        } else {
            rec.toFire = h?.let { (horizontal(it, fire.position) - DrillRules.ARRIVE_M).coerceAtLeast(0f) } ?: 0f
        }
        routeRec = rec
        selectedId = null
        mode = Mode.ROUTE_RECORDING
        FireLog.i("ROUTE start exit=$exitId phase=${rec.phase}")
    }

    private fun routeStep(pos: Vec3) {
        val rec = routeRec ?: return
        val fire = fireMarker() ?: return
        val exit = markers.firstOrNull { it.id == rec.exitId } ?: return
        val f = foot(pos)
        when (rec.phase) {
            RoutePhase.TO_FIRE -> {
                val d = horizontal(pos, fire.position)
                rec.toFire = (d - DrillRules.ARRIVE_M).coerceAtLeast(0f)
                if (d <= DrillRules.ARRIVE_M) {
                    rec.phase = RoutePhase.TO_EXIT
                    rec.points.clear()
                    rec.points += f
                    rec.raw.clear()
                    FireLog.i("ROUTE at fire, recording")
                }
            }
            RoutePhase.TO_EXIT -> {
                rec.raw.addLast(f)
                while (rec.raw.size > SMOOTH_N) rec.raw.removeFirst()
                var avg = Vec3.ZERO
                for (p in rec.raw) avg += p
                avg /= rec.raw.size.toFloat()
                val last = rec.points.lastOrNull()
                if (last == null || horizontal(avg, last) >= DrillRules.ROUTE_STEP_M) {
                    if (last != null) rec.length += horizontal(avg, last)
                    rec.points += avg
                }
                if (horizontal(pos, exit.position) <= DrillRules.ARRIVE_M) finishRoute(rec)
            }
            else -> Unit
        }
        publish()
    }

    /** v0.1.1 (taste profile B17): the route as a straight line from the fire spot to the exit, drawn without walking. */
    private fun autoRoute(rec: RouteRec) {
        val fire = fireMarker() ?: return
        val exit = markers.firstOrNull { it.id == rec.exitId && it.found } ?: return
        val a = Vec3(fire.position.x, floorY, fire.position.z)
        val b = Vec3(exit.position.x, floorY, exit.position.z)
        val len = horizontal(a, b)
        val n = ceil(len / DrillRules.ROUTE_STEP_M).toInt().coerceAtLeast(1)
        rec.points.clear()
        rec.raw.clear()
        for (i in 0..n) rec.points += a.lerp(b, i.toFloat() / n)
        rec.length = len
        rec.phase = RoutePhase.TO_EXIT
        FireLog.i("ROUTE auto exit=${rec.exitId} length=${FireLog.f(len)} points=${rec.points.size}")
        finishRoute(rec)
    }

    private fun finishRoute(rec: RouteRec) {
        if (rec.length < DrillRules.ROUTE_MIN_M || rec.points.size < DrillRules.ROUTE_MIN_POINTS) {
            rec.phase = RoutePhase.SHORT
            FireLog.i("ROUTE short length=${FireLog.f(rec.length)} points=${rec.points.size}")
        } else {
            rec.phase = RoutePhase.DONE
            routes[rec.exitId] = Route(rec.exitId, rec.points.toList())
            routesDirty = true
            FireLog.i("ROUTE done exit=${rec.exitId} length=${FireLog.f(rec.length)} points=${rec.points.size}")
            emit(FireUiEffect.Sound(SoundKind.ARRIVE))
            save()
        }
        mode = Mode.ROUTE_DONE
    }

    // ---- the drill

    private fun startDrill(forced: Scenario?, skipCountdown: Boolean) {
        val fire = fireMarker()
        if (fire == null || visible(MarkerKind.EXIT).isEmpty()) return
        val scenario =
            forced
                ?: when (settings.scenario) {
                    ScenarioPref.SMALL -> Scenario.SMALL
                    ScenarioPref.BIG -> Scenario.BIG
                    ScenarioPref.RANDOM -> if (Math.random() < 0.7) Scenario.SMALL else Scenario.BIG
                }
        val d = Drill(scenario)
        roomEdit = false
        listOpen = false
        d.alarmMarkerId = visible(MarkerKind.DETECTOR).minByOrNull { horizontal(it.position, fire.position) }?.id
        drill = d
        rearmFar()
        mode = Mode.DRILL
        forcedMode = null
        selectedId = null
        menuOpen = false
        lastResult = null
        FireLog.i("DRILL start scenario=${scenario.tag} alarm=${d.alarmMarkerId ?: "fire"}")
        if (skipCountdown) ignite(d)
        tickJob?.cancel()
        tickJob =
            viewModelScope.launch {
                while (isActive && drill === d) {
                    delay(TICK_MS)
                    if (drill === d) tick(d, TICK_MS / 1000f)
                }
            }
    }

    private fun rearmFar() {
        val h = head
        for (m in markers) wasFar[m.id] = h == null || horizontal(h, m.position) > DrillRules.LEAVE_M
        nearSince.clear()
    }

    private fun ignite(d: Drill) {
        d.countdown = 0f
        if (d.scenario == Scenario.BIG) {
            d.level = 2
            d.phase = DrillPhase.EVACUATE
            d.evacAt = 0f
        } else {
            d.phase = DrillPhase.FIRE
        }
        rearmFar()
        FireLog.i("DRILL fire level=${d.level} phase=${d.phase}")
    }

    private fun tick(d: Drill, dt: Float) {
        if (d.paused || mode != Mode.DRILL) {
            publish()
            return
        }
        if (d.phase == DrillPhase.COUNTDOWN) {
            val h = head
            d.blockedAtDoor = h != null && visible(MarkerKind.EXIT).any { horizontal(h, it.position) < DrillRules.DOOR_CLEAR_M }
            if (!d.blockedAtDoor && !d.holdCountdown) d.countdown -= dt
            if (d.countdown <= 0f) ignite(d)
            publish()
            return
        }
        d.elapsed += dt
        if (!d.spraying && !d.fireOut) {
            d.grow += dt
            if (d.grow >= DrillRules.LEVEL_UP_S && d.level < 3) {
                d.level++
                d.grow = 0f
                d.sweeps = 0
                d.side = 0
                FireLog.i("DRILL level up level=${d.level}")
                if (d.level >= 2 && d.phase != DrillPhase.EVACUATE && d.phase != DrillPhase.OUT) toEvac(d, empty = false)
            }
        }
        if (d.phase == DrillPhase.SWEEP) {
            val bad = distanceCheck(d)
            if (bad != null && d.distanceBad == null) d.wrongDist++
            d.distanceBad = bad
            if (d.spraying) {
                d.sprayLeft -= dt
                if (d.sprayLeft <= 0f) {
                    d.sprayLeft = 0f
                    FireLog.i("DRILL extinguisher empty")
                    toEvac(d, empty = true)
                }
            }
        }
        publish()
    }

    private fun distanceCheck(d: Drill): DistanceBad? {
        d.distanceOverride?.let { return if (it == "near") DistanceBad.NEAR else if (it == "far") DistanceBad.FAR else null }
        val h = head ?: return null
        val fire = fireMarker() ?: return null
        val dist = horizontal(h, fire.position)
        return when {
            dist < DrillRules.NEAR_M -> DistanceBad.NEAR
            dist > DrillRules.FAR_M -> DistanceBad.FAR
            else -> null
        }
    }

    private fun toEvac(d: Drill, empty: Boolean) {
        d.phase = DrillPhase.EVACUATE
        d.spraying = false
        d.aim = AimTarget.NONE
        if (d.level < 2) d.level = 2
        d.grow = 0f
        if (d.evacAt == null) d.evacAt = d.elapsed
        if (empty) d.empty = true
        FireLog.i("DRILL evacuate empty=$empty level=${d.level}")
    }

    private fun drillHead(pos: Vec3) {
        val d = drill ?: return
        if (d.paused || trackingLost || d.phase == DrillPhase.COUNTDOWN) return
        val now = SystemClock.elapsedRealtime()
        val f = foot(pos)
        val last = d.trail.lastOrNull()
        if (last == null || horizontal(last, f) >= DrillRules.ROUTE_STEP_M) d.trail += f
        for (ext in visible(MarkerKind.EXTINGUISHER)) {
            if (!arrived(ext, needFar = true, now = now)) continue
            onArrivedExtinguisher(d)
        }
        for (exit in visible(MarkerKind.EXIT)) {
            if (arrived(exit, needFar = true, now = now)) {
                FireLog.i("DRILL arrive exit=${exit.id}")
                finishDrill(d)
                return
            }
        }
    }

    private fun onArrivedExtinguisher(d: Drill) {
        when (d.phase) {
            DrillPhase.FIRE -> {
                d.phase = DrillPhase.GOT
                d.gotAt = d.elapsed
                FireLog.i("DRILL got extinguisher at=${FireLog.f(d.elapsed)}")
                emit(FireUiEffect.Sound(SoundKind.ARRIVE))
            }
            DrillPhase.EVACUATE ->
                if (d.gotAt == null && !d.dontFight) {
                    d.f1 = true
                    d.dontFight = true
                    FireLog.i("DRILL fetched too late (F1)")
                }
            else -> Unit
        }
    }

    private fun arrived(m: Marker, needFar: Boolean, now: Long): Boolean {
        val h = head ?: return false
        val d = horizontal(h, m.position)
        if (d > DrillRules.LEAVE_M) {
            wasFar[m.id] = true
            nearSince.remove(m.id)
            return false
        }
        if (d <= DrillRules.ARRIVE_M && (!needFar || wasFar[m.id] == true)) {
            val since = nearSince.getOrPut(m.id) { now }
            if (now - since >= DrillRules.ARRIVE_HOLD_MS) {
                wasFar[m.id] = false
                nearSince.remove(m.id)
                return true
            }
        }
        return false
    }

    private fun pullPin() {
        val d = drill ?: return
        if (d.paused || d.phase != DrillPhase.GOT) return
        d.phase = DrillPhase.AIM
        FireLog.i("DRILL pin pulled")
        emit(FireUiEffect.Sound(SoundKind.PIN))
    }

    private fun onAim(target: AimTarget) {
        val d = drill ?: return
        if (d.aim == target) return
        d.aim = target
        if (d.phase == DrillPhase.AIM || d.phase == DrillPhase.SWEEP) FireLog.i("DRILL aim=$target")
    }

    private fun onSprayStarted(onRing: Boolean) {
        val d = drill ?: return
        if (d.paused) return
        when (d.phase) {
            DrillPhase.AIM -> {
                if (!onRing) {
                    d.aim = AimTarget.TOP
                    return
                }
                d.phase = DrillPhase.SWEEP
                d.spraying = true
                d.side = 0
                d.aim = AimTarget.RING
                FireLog.i("DRILL spray start sweeps=${d.sweeps}")
            }
            DrillPhase.SWEEP -> {
                d.spraying = true
                d.side = 0
            }
            else -> Unit
        }
    }

    private fun onSprayMoved(lateral: Float) {
        val d = drill ?: return
        if (d.phase != DrillPhase.SWEEP || !d.spraying || d.paused) return
        val r = DrillRules.ringRadius(d.level)
        val k = lateral / r
        val side = if (k < -0.5f) -1 else if (k > 0.5f) 1 else 0
        if (side == 0) return
        if (d.side != 0 && side != d.side && d.distanceBad == null) {
            d.sweeps++
            FireLog.i("DRILL sweep n=${d.sweeps}")
            if (d.sweeps >= DrillRules.SWEEPS_NEEDED) putOut(d)
            publish()
        }
        d.side = side
    }

    private fun putOut(d: Drill) {
        d.fireOut = true
        d.outAt = d.elapsed
        d.phase = DrillPhase.OUT
        d.spraying = false
        d.aim = AimTarget.NONE
        FireLog.i("DRILL fire out at=${FireLog.f(d.elapsed)}")
        emit(FireUiEffect.Sound(SoundKind.OUT))
    }

    private fun onSprayEnded() {
        val d = drill ?: return
        if (d.phase == DrillPhase.SWEEP) {
            d.spraying = false
            d.side = 0
            if (!d.fireOut) d.phase = DrillPhase.AIM
            FireLog.i("DRILL spray end")
        }
    }

    private fun endDrill() {
        if (drill == null) return
        drill = null
        tickJob?.cancel()
        mode = Mode.HOME
        FireLog.i("DRILL end (no result)")
    }

    private fun finishDrill(d: Drill) {
        val end = d.elapsed
        val fightEnd = d.outAt ?: d.evacAt ?: end
        val segs = ArrayList<ResultSegment>()
        val got = d.gotAt
        if (got != null) {
            segs += ResultSegment(0, got)
            segs += ResultSegment(1, (fightEnd - got).coerceAtLeast(0f))
            segs += ResultSegment(2, (end - fightEnd).coerceAtLeast(0f))
        } else {
            segs += ResultSegment(2, end)
        }
        val fire = fireMarker()
        val exts = visible(MarkerKind.EXTINGUISHER)
        val toMove = detectorsToMove()
        val summary =
            DrillSummary(
                totalS = end,
                gotAtS = got,
                fetchedWhenTooBig = d.f1,
                nearestExtinguisherM = if (fire != null && exts.isNotEmpty()) exts.minOf { horizontal(it.position, fire.position) } else null,
                exitCount = visibleCount(MarkerKind.EXIT),
                detectorCount = visibleCount(MarkerKind.DETECTOR),
                detectorsToMove = toMove,
                wrongDistanceCount = d.wrongDist,
            )
        val findings = findingsRule.findings(summary)
        val prev = records.lastOrNull()?.totalS
        records += DrillRecord(java.lang.System.currentTimeMillis(), d.scenario, end)
        while (records.size > 5) records.removeAt(0)
        lastResult = ResultUi(end, d.scenario, segs, findings, toMove, prev, d.trail.toList())
        drill = null
        tickJob?.cancel()
        mode = Mode.RESULT
        FireLog.i("DRILL finish total=${FireLog.f(end)} scenario=${d.scenario.tag} findings=${findings.joinToString(",")}")
        emit(FireUiEffect.Sound(SoundKind.DONE))
        save()
    }

    private fun detectorsToMove(): Int {
        val fire = fireMarker()
        return visible(MarkerKind.DETECTOR).count { detectorRule.check(it, fire, planeTracker.all).anyFail }
    }

    private fun showLastResult() {
        val r = lastResult
        if (r != null) {
            mode = Mode.RESULT
            return
        }
        val last = records.lastOrNull() ?: return
        val fire = fireMarker()
        val exts = visible(MarkerKind.EXTINGUISHER)
        val toMove = detectorsToMove()
        val summary =
            DrillSummary(
                totalS = last.totalS,
                gotAtS = null,
                fetchedWhenTooBig = false,
                nearestExtinguisherM = if (fire != null && exts.isNotEmpty()) exts.minOf { horizontal(it.position, fire.position) } else null,
                exitCount = visibleCount(MarkerKind.EXIT),
                detectorCount = visibleCount(MarkerKind.DETECTOR),
                detectorsToMove = toMove,
                wrongDistanceCount = 0,
            )
        val prev = if (records.size >= 2) records[records.size - 2].totalS else null
        lastResult = ResultUi(last.totalS, last.scenario, listOf(ResultSegment(2, last.totalS)), findingsRule.findings(summary), toMove, prev, emptyList())
        mode = Mode.RESULT
    }

    // ---- head

    private fun onHead(pos: Vec3, forward: Vec3) {
        head = pos
        headForward = forward
        checkBlocked()
        when (mode) {
            Mode.ROUTE_RECORDING -> routeStep(pos)
            Mode.DRILL -> drillHead(pos)
            else -> if (listOpen) publish()
        }
    }

    // ---- debug hooks

    private fun seed() {
        val planes = planeTracker.all
        for (m in markers) m.anchorId?.let { uuid -> viewModelScope.launch { anchors.remove(uuid) } }
        markers.clear()
        routes.clear()
        storedRoutes = emptyList()
        markersDirty = true
        routesDirty = true
        val h = head ?: Vec3(0f, 1.6f, 0f)
        var fwd = Vec3(headForward.x, 0f, headForward.z)
        if (fwd.length < 0.1f) fwd = Vec3.AHEAD
        fwd = fwd.normalized()
        val right = Vec3(-fwd.z, 0f, fwd.x)
        val floor = planes.filter { it.kind == SurfaceKind.FLOOR }.maxByOrNull { it.vertices.size }
        val fy = PlaceMarkerUseCase.floorHeight(planes) ?: floorY
        fun onFloor(p: Vec3) = Vec3(p.x, fy, p.z)
        fun inside(p: Vec3) = floor == null || hits.contains(floor, p, margin = 0.3f)
        fun pick(candidates: List<Vec3>): Vec3 = candidates.firstOrNull { inside(it) } ?: candidates.first()
        // the fire spot: a table / cabinet top inside 30 degrees of the view and 5 m, else the floor 2.8 m ahead, a little left
        val table =
            planes
                .filter { (it.kind == SurfaceKind.TABLE || it.kind == SurfaceKind.CABINET) && it.horizontal && it.centroid.y > fy + 0.4f && it.centroid.y < fy + 1.4f }
                .filter { p ->
                    val to = Vec3(p.centroid.x - h.x, 0f, p.centroid.z - h.z)
                    val d = to.length
                    d in 1.2f..5f && to.dot(fwd) / d > 0.87f
                }
                .minByOrNull { it.centroid.distanceTo(h) }
        // on the floor: 1.8 m ahead and 0.9 m right, in the open (the emulator's bed platform hides anything behind it)
        val fire = table?.centroid ?: pick(listOf(onFloor(h + fwd * 1.8f + right * 0.9f), onFloor(h + fwd * 2f), onFloor(h + fwd * 1.5f)))
        val ext = pick(listOf(onFloor(h + fwd * 3.2f + right * 2.3f), onFloor(h + fwd * 2.5f + right * 1.2f), onFloor(h + fwd * 1.5f + right * 1f)))
        val doors = planes.filter { it.kind == SurfaceKind.DOOR }
        fun doorFoot(d: PlaneSurface): Vec3 {
            val n = d.normal
            var flat = Vec3(n.x, 0f, n.z).normalized()
            if ((h - d.centroid).dot(flat) < 0f) flat = -flat
            return onFloor(d.centroid + flat * PlaceMarkerUseCase.DOOR_OFFSET_M)
        }
        val exit1 = pick(listOf(onFloor(h + fwd * 4.6f + right * 1.3f), onFloor(h + fwd * 3.8f + right * 1.6f), onFloor(h + fwd * 3f + right * 2f)))
        val exit2 = doors.getOrNull(0)?.let(::doorFoot) ?: pick(listOf(onFloor(h - fwd * 3f + right * 1f), onFloor(h + right * 4f), onFloor(h - right * 4f)))
        val ceiling = planes.firstOrNull { it.kind == SurfaceKind.CEILING }
        val cy = ceiling?.centroid?.y ?: (fy + 2.5f)
        fun detector(id: String, p: Vec3): Marker {
            val pos = Vec3(p.x, cy, p.z)
            return Marker(id, MarkerKind.DETECTOR, pos, onCeiling = ceiling != null, belowCeilingM = if (ceiling == null) 0.18f else null, wallDistM = detectorRule.wallDistance(pos, planes) ?: 0.62f)
        }
        val list =
            listOf(
                Marker(newId(), MarkerKind.FIRE, fire, onTable = table != null),
                Marker(newId(), MarkerKind.EXTINGUISHER, ext),
                Marker(newId(), MarkerKind.EXIT, exit1),
                Marker(newId(), MarkerKind.EXIT, exit2),
                detector(newId(), onFloor(h + fwd * 2.4f + right * 0.2f)),
                detector(newId(), onFloor(h + fwd * 5f + right * 1.4f)),
            )
        for (m in list) {
            markers += m.copy(found = true, saved = false)
            createAnchor(m.id)
        }
        // one route from the fire spot to the first exit, 0.4 m apart
        fun straight(a: Vec3, b: Vec3): List<Vec3> {
            val n = (horizontal(a, b) / DrillRules.ROUTE_STEP_M).toInt().coerceAtLeast(4)
            return (0..n).map { i -> a.lerp(b, i.toFloat() / n) }
        }
        val pts = straight(onFloor(fire), exit1)
        routes[list[2].id] = Route(list[2].id, pts)
        routes[list[3].id] = Route(list[3].id, straight(onFloor(fire), exit2))
        if (records.isEmpty()) records += DrillRecord(java.lang.System.currentTimeMillis() - 86_400_000L, Scenario.SMALL, 116f)
        settings = settings.copy(coachSeen = true)
        coachVisible = false
        selectedId = null
        routeRec = null
        drill = null
        tickJob?.cancel()
        dialog = null
        menuOpen = false
        forcedMode = null
        placeKind = MarkerKind.DETECTOR
        if (mode == Mode.STARTING || mode == Mode.SCANNING || mode == Mode.RESTORING) mode = Mode.HOME
        else if (mode != Mode.PLACING) mode = Mode.HOME
        FireLog.i("SEED fire=${FireLog.pos(fire)} ext=${FireLog.pos(ext)} exit1=${FireLog.pos(exit1)} exit2=${FireLog.pos(exit2)} route=${pts.size}")
        save()
    }

    private fun debugPlace(kind: MarkerKind, point: Vec3?) {
        val planes = planeTracker.all
        val h = head ?: Vec3(0f, 1.6f, 0f)
        var fwd = Vec3(headForward.x, 0f, headForward.z)
        if (fwd.length < 0.1f) fwd = Vec3.AHEAD
        fwd = fwd.normalized()
        val fy = PlaceMarkerUseCase.floorHeight(planes) ?: floorY
        val p =
            point
                ?: when (kind) {
                    MarkerKind.FIRE -> planes.firstOrNull { (it.kind == SurfaceKind.TABLE || it.kind == SurfaceKind.CABINET) && it.horizontal }?.centroid ?: Vec3(h.x + fwd.x * 2f, fy, h.z + fwd.z * 2f)
                    MarkerKind.EXTINGUISHER -> Vec3(h.x + fwd.x * 3f - fwd.z, fy, h.z + fwd.z * 3f + fwd.x)
                    MarkerKind.EXIT -> Vec3(h.x + fwd.x * 5f, fy, h.z + fwd.z * 5f)
                    MarkerKind.DETECTOR -> Vec3(h.x + fwd.x * 2f, planes.firstOrNull { it.kind == SurfaceKind.CEILING }?.centroid?.y ?: (fy + 2.5f), h.z + fwd.z * 2f)
                }
        // snap to a fitting surface when one is near the point
        val fitting =
            planes.filter { pl ->
                when (kind) {
                    MarkerKind.FIRE -> pl.kind == SurfaceKind.FLOOR || ((pl.kind == SurfaceKind.TABLE || pl.kind == SurfaceKind.CABINET) && pl.horizontal)
                    MarkerKind.EXTINGUISHER, MarkerKind.EXIT -> pl.kind == SurfaceKind.FLOOR
                    MarkerKind.DETECTOR -> pl.kind == SurfaceKind.CEILING || (pl.kind == SurfaceKind.WALL && !PlaceMarkerUseCase.hasCeiling(planes))
                }
            }
        val plane = fitting.filter { hits.distanceToPlane(it, p) < 0.6f && hits.contains(it, p, margin = 0.3f) }.minByOrNull { hits.distanceToPlane(it, p) }
        val marker =
            if (plane != null) {
                when (val out = placeRule.place(newId(), kind, plane, hits.project(plane, p), h, planes, room?.floorY ?: fy, room?.ceilingY ?: (fy + RoomBox.DEFAULT_H))) {
                    is PlaceMarkerUseCase.Outcome.Place -> out.marker
                    PlaceMarkerUseCase.Outcome.Refused -> Marker(newId(), kind, p)
                }
            } else {
                Marker(newId(), kind, p, onTable = kind == MarkerKind.FIRE && point == null && planes.any { (it.kind == SurfaceKind.TABLE || it.kind == SurfaceKind.CABINET) && it.horizontal }, onCeiling = kind == MarkerKind.DETECTOR && PlaceMarkerUseCase.hasCeiling(planes), belowCeilingM = if (kind == MarkerKind.DETECTOR && !PlaceMarkerUseCase.hasCeiling(planes)) 0.18f else null)
            }
        if (mode == Mode.HOME || mode == Mode.STARTING || mode == Mode.SCANNING) enterPlacing()
        placeKind = kind
        addMarker(marker, via = "debug")
    }

    private fun debugArrive(target: String) {
        val m =
            when (target) {
                "fire" -> fireMarker()
                "ext" -> visible(MarkerKind.EXTINGUISHER).firstOrNull()
                "exit", "exit1" -> visible(MarkerKind.EXIT).getOrNull(0)
                "exit2" -> visible(MarkerKind.EXIT).getOrNull(1)
                else -> markers.firstOrNull { it.id == target && it.found }
            } ?: return
        head = m.position + Vec3(0f, 1.6f, 0f)
        FireLog.i("DEBUG arrive id=${m.id} kind=${m.kind.tag}")
        when (mode) {
            Mode.ROUTE_RECORDING -> {
                val rec = routeRec ?: return
                if (rec.phase == RoutePhase.TO_FIRE) {
                    rec.phase = RoutePhase.TO_EXIT
                    rec.points.clear()
                    rec.points += foot(m.position)
                } else if (m.kind == MarkerKind.EXIT) {
                    // a straight line to the exit stands in for the walk
                    val a = rec.points.lastOrNull() ?: foot(m.position)
                    val b = foot(m.position)
                    val n = (horizontal(a, b) / DrillRules.ROUTE_STEP_M).toInt().coerceAtLeast(1)
                    for (i in 1..n) {
                        val p = a.lerp(b, i.toFloat() / n)
                        rec.length += horizontal(p, rec.points.last())
                        rec.points += p
                    }
                    finishRoute(rec)
                }
            }
            Mode.DRILL -> {
                val d = drill ?: return
                if (m.kind == MarkerKind.EXTINGUISHER) onArrivedExtinguisher(d)
                else if (m.kind == MarkerKind.EXIT && d.phase != DrillPhase.COUNTDOWN) finishDrill(d)
            }
            else -> Unit
        }
    }

    private fun debugSweep(count: Int) {
        val d = drill ?: return
        if (d.phase == DrillPhase.GOT) d.phase = DrillPhase.AIM
        if (d.phase == DrillPhase.AIM) {
            d.phase = DrillPhase.SWEEP
            d.spraying = true
        }
        if (d.phase != DrillPhase.SWEEP) return
        d.sweeps = count.coerceAtLeast(0)
        if (d.sweeps >= DrillRules.SWEEPS_NEEDED) putOut(d)
    }

    /** Straight to a designed state (see `DebugCommands`). */
    private fun debugState(spec: String) {
        val parts = spec.trim().split(Regex("\\s+"))
        val name = parts.firstOrNull()?.uppercase(Locale.US) ?: return
        val arg = parts.getOrNull(1)?.lowercase(Locale.US)
        forcedMode = null
        dialog = null
        menuOpen = false
        FireGraph.head.forceLost = false
        // a state outside the drill ends a running (or paused) drill first; sticky snackbars of forced states go away
        if (name !in DRILL_STATES) {
            drill = null
            tickJob?.cancel()
            if (mode == Mode.DRILL) mode = Mode.HOME
        }
        if (name != "S09") {
            restoreJob?.cancel()
            restoring = null
            clearSnack(SnackKind.RESTORING)
        }
        if (name != "S01") clearSnack(SnackKind.SCANNING)
        if (name != "M2") clearSnack(SnackKind.LOST)
        roomEdit = false
        listOpen = false
        selectedId = null
        routeRec = null
        fun ensureSeed() {
            if (fireMarker() == null || visible(MarkerKind.EXIT).isEmpty()) seed()
        }
        fun runDrill(scenario: Scenario) {
            ensureSeed()
            startDrill(scenario, skipCountdown = true)
        }
        when (name) {
            "S00" -> {
                forcedMode = Mode.STARTING
                mode = Mode.STARTING
            }
            "LIST" -> {
                if (fireMarker() == null) seed()
                mode = Mode.PLACING
                listOpen = true
            }
            "ROOM" -> {
                if (room == null) createRoom()
                if (mode != Mode.PLACING) mode = Mode.HOME
                roomEdit = true
            }
            "S01" -> {
                forcedMode = Mode.SCANNING
                mode = Mode.SCANNING
                showSnack(SnackKind.SCANNING)
            }
            "S02" -> {
                drill = null
                tickJob?.cancel()
                for (m in markers) m.anchorId?.let { uuid -> viewModelScope.launch { anchors.remove(uuid) } }
                markers.clear()
                routes.clear()
                markersDirty = true
                routesDirty = true
                placeKind = MarkerKind.FIRE
                mode = Mode.HOME
                save()
            }
            "S03" -> {
                drill = null
                tickJob?.cancel()
                ensureSeed()
                mode = Mode.HOME
            }
            "S04" -> {
                drill = null
                tickJob?.cancel()
                ensureSeed()
                settings = settings.copy(coachSeen = true)
                enterPlacing()
            }
            "S05" -> {
                drill = null
                tickJob?.cancel()
                ensureSeed()
                settings = settings.copy(coachSeen = true)
                enterPlacing()
                selectedId =
                    when (arg) {
                        "det" -> visible(MarkerKind.DETECTOR).getOrNull(0)?.id
                        "det2" -> visible(MarkerKind.DETECTOR).getOrNull(1)?.id
                        "exit" -> visible(MarkerKind.EXIT).getOrNull(0)?.id
                        else -> visible(MarkerKind.EXTINGUISHER).firstOrNull()?.id
                    }
            }
            "S06" -> {
                ensureSeed()
                enterPlacing()
                mode = Mode.ROUTE_PICK
            }
            "S07" -> {
                ensureSeed()
                enterPlacing()
                startRoute(visible(MarkerKind.EXIT)[0].id)
            }
            "S08" -> {
                ensureSeed()
                enterPlacing()
                val exit = visible(MarkerKind.EXIT)[0]
                val rec = RouteRec(exit.id)
                val r = routes[exit.id]
                if (r != null && arg != "short") {
                    rec.points += r.points
                    rec.length = r.lengthM
                    rec.phase = RoutePhase.DONE
                } else {
                    rec.phase = RoutePhase.SHORT
                    rec.length = 0.8f
                }
                routeRec = rec
                mode = Mode.ROUTE_DONE
            }
            "S09" -> {
                forcedMode = Mode.RESTORING
                mode = Mode.RESTORING
                restoring = 0 to maxOf(1, markers.size)
                showSnack(SnackKind.RESTORING)
            }
            "S10" -> {
                if (mode == Mode.RESTORING || mode == Mode.STARTING) mode = Mode.HOME
                dialog = DialogKind.NOT_HERE
            }
            "S11" -> {
                ensureSeed()
                startDrill(Scenario.SMALL, skipCountdown = false)
                drill?.holdCountdown = arg != "run"
                if (arg == "door") drill?.blockedAtDoor = true
            }
            "S12" -> runDrill(Scenario.SMALL)
            "S13" -> {
                runDrill(Scenario.SMALL)
                drill?.let { onArrivedExtinguisher(it) }
            }
            "S14" -> {
                runDrill(Scenario.SMALL)
                drill?.let { onArrivedExtinguisher(it) }
                pullPin()
                if (arg == "top") onAim(AimTarget.TOP) else if (arg == "ring") onAim(AimTarget.RING)
            }
            "S15", "S15+M1", "M1" -> {
                if (name != "M1" || drill == null) {
                    runDrill(Scenario.SMALL)
                    drill?.let { onArrivedExtinguisher(it) }
                    pullPin()
                    onSprayStarted(true)
                    drill?.sweeps = 2
                }
                drill?.distanceOverride = if (name == "S15") "ok" else if (arg == "far") "far" else "near"
            }
            "S16" -> {
                runDrill(Scenario.SMALL)
                drill?.let { onArrivedExtinguisher(it) }
                pullPin()
                drill?.distanceOverride = "ok"
                onSprayStarted(true)
                debugSweep(DrillRules.SWEEPS_NEEDED)
            }
            "S17" -> runDrill(Scenario.BIG)
            "S18" -> {
                ensureSeed()
                startDrill(Scenario.SMALL, skipCountdown = true)
                drill?.let { d ->
                    d.elapsed = 102f
                    d.gotAt = 20f
                    d.phase = DrillPhase.OUT
                    d.fireOut = true
                    d.outAt = 55f
                    d.wrongDist = if (arg == "bad") 2 else 0
                    d.trail += foot(fireMarker()?.position ?: Vec3.ZERO)
                    visible(MarkerKind.EXIT).firstOrNull()?.let { d.trail += foot(it.position) }
                    finishDrill(d)
                }
            }
            "S19" -> {
                runDrill(Scenario.SMALL)
                drill?.paused = true
            }
            "S20" -> {
                if (mode == Mode.STARTING || mode == Mode.SCANNING) mode = Mode.HOME
                menuOpen = true
            }
            "S21" -> dialog = DialogKind.CLEAR
            "S22" -> dialog = DialogKind.UNAVAILABLE
            "S23" -> {
                settings = settings.copy(coachSeen = false)
                enterPlacing()
            }
            "M2" -> FireGraph.head.forceLost = true
            else -> FireLog.i("CMD state unknown '$spec'")
        }
        FireLog.i("DEBUG state $spec -> ${buildState().stateName}")
    }

    // ---- helpers

    private fun visible(kind: MarkerKind): List<Marker> = markers.filter { it.found && it.kind == kind }

    private fun visibleCount(kind: MarkerKind): Int = markers.count { it.found && it.kind == kind }

    private fun full(kind: MarkerKind): Boolean = visibleCount(kind) >= kind.limit

    private fun fireMarker(): Marker? = markers.firstOrNull { it.found && it.kind == MarkerKind.FIRE }

    private fun nextMissing(): MarkerKind {
        for (k in listOf(MarkerKind.FIRE, MarkerKind.EXIT, MarkerKind.EXTINGUISHER, MarkerKind.DETECTOR)) if (visibleCount(k) == 0) return k
        return listOf(MarkerKind.DETECTOR, MarkerKind.EXTINGUISHER, MarkerKind.EXIT).firstOrNull { !full(it) } ?: MarkerKind.DETECTOR
    }

    private fun newId(): String = "m${uid++}"

    private fun foot(p: Vec3): Vec3 = Vec3(p.x, floorY, p.z)

    private fun emit(effect: FireUiEffect) {
        if (effect is FireUiEffect.Sound && settings.muted) return
        _effects.tryEmit(effect)
    }

    private fun showSnack(kind: SnackKind, a: Int = 0, b: Int = 0) {
        val id = ++snackSerial
        snack = SnackUi(id, kind, a, b)
        snackJob?.cancel()
        if (kind.durationMs > 0) {
            snackJob =
                viewModelScope.launch {
                    delay(kind.durationMs)
                    if (snack?.id == id) {
                        snack = null
                        publish()
                    }
                }
        }
        FireLog.i("SNACK ${kind.name.lowercase(Locale.US)} id=$id")
    }

    private fun clearSnack(kind: SnackKind) {
        if (snack?.kind == kind) snack = null
    }

    private fun save() {
        val session = StoredSession(markers.toList(), storedRoutesNow(), records.takeLast(5), settings)
        viewModelScope.launch { runCatching { repository.save(session) }.onFailure { FireLog.e("SAVE failed: $it", it) } }
    }

    private fun storedRoutesNow(): List<StoredRoute> =
        routes.values.mapNotNull { r ->
            val exit = markers.firstOrNull { it.id == r.exitId } ?: return@mapNotNull null
            val pose = exit.anchorId?.let { anchorPoses[it] } ?: AnchorPose(exit.position - exit.offset, Quat4.IDENTITY)
            val inv = pose.rotation.conjugate()
            StoredRoute(r.exitId, r.points.map { inv.rotate(it - pose.position) })
        }

    private fun markersUi(): List<MarkerUi> {
        if (!markersDirty) return markersUi
        val fire = fireMarker()
        val exits = visible(MarkerKind.EXIT)
        markersUi =
            markers.filter { it.found }.map { m ->
                MarkerUi(
                    id = m.id,
                    kind = m.kind,
                    position = m.position,
                    onTable = m.onTable,
                    onCeiling = m.onCeiling,
                    exitIndex = if (m.kind == MarkerKind.EXIT && exits.size >= 2) exits.indexOf(m) + 1 else 0,
                    fromFireM = if (fire != null && m.kind != MarkerKind.FIRE) horizontal(m.position, fire.position) else null,
                    saved = m.saved,
                )
            }
        markersDirty = false
        return markersUi
    }

    private fun routesUi(): Map<String, List<Vec3>> {
        if (!routesDirty) return routesUi
        routesUi = routes.mapValues { (_, r) -> r.points.map { Vec3(it.x, floorY, it.z) } }
        routesDirty = false
        return routesUi
    }

    private fun hintOf(d: Drill): HintKind =
        when (d.phase) {
            DrillPhase.COUNTDOWN -> if (d.blockedAtDoor) HintKind.LEAVE_DOOR else HintKind.COUNTDOWN_SAFETY
            DrillPhase.FIRE -> if (d.elapsed < DrillRules.FIRST_HINT_S || !settings.hints) HintKind.FIRE_START else HintKind.GET_EXT
            DrillPhase.GOT -> if (settings.hints) HintKind.BACK_PULL else HintKind.NONE
            DrillPhase.AIM -> if (d.aim == AimTarget.TOP) HintKind.AIM_LOW else if (settings.hints) HintKind.AIM else HintKind.NONE
            DrillPhase.SWEEP ->
                when (d.distanceBad) {
                    DistanceBad.NEAR -> HintKind.TOO_CLOSE
                    DistanceBad.FAR -> HintKind.TOO_FAR
                    null -> HintKind.SWEEP
                }
            DrillPhase.OUT -> HintKind.OUT
            DrillPhase.EVACUATE -> if (d.empty) HintKind.EMPTY else if (d.dontFight) HintKind.DONT_FIGHT else HintKind.TOO_BIG
        }

    private fun buildState(): FireUiState {
        val d = drill
        val rec = routeRec
        val sel = selectedId
        val selected = sel?.let { id -> markers.firstOrNull { it.id == id && it.found } }
        val detector =
            if (selected != null && selected.kind == MarkerKind.DETECTOR && mode == Mode.PLACING) {
                val c = detectorRule.check(selected, fireMarker(), planeTracker.all)
                DetectorCheckUi(c.onCeiling, c.wallDistM, c.belowCeilingM, c.stoveDistM, c.wallOk, c.topOk, c.stoveOk)
            } else null
        return FireUiState(
            mode = mode,
            markers = markersUi(),
            routes = routesUi(),
            placeKind = placeKind,
            selectedId = if (mode == Mode.PLACING) selected?.id else null,
            detector = detector,
            routeRec = rec?.let { RouteRecUi(it.exitId, it.phase, it.toFire, it.length, it.points.toList()) },
            drill =
                d?.let {
                    DrillUi(
                        phase = it.phase,
                        scenario = it.scenario,
                        elapsedS = it.elapsed,
                        countdown = kotlin.math.ceil(it.countdown.toDouble()).toInt().coerceIn(1, 3),
                        level = it.level,
                        sprayLeftS = it.sprayLeft,
                        spraying = it.spraying,
                        sweeps = it.sweeps,
                        distanceBad = if (it.phase == DrillPhase.SWEEP) it.distanceBad else null,
                        aim = it.aim,
                        blockedAtDoor = it.blockedAtDoor,
                        paused = it.paused,
                        empty = it.empty,
                        dontFight = it.dontFight,
                        hint = hintOf(it),
                        fireOut = it.fireOut,
                        alarmMarkerId = it.alarmMarkerId,
                    )
                },
            result = if (mode == Mode.RESULT) lastResult else null,
            lastTotalS = records.lastOrNull()?.totalS,
            menuOpen = menuOpen && dialog == null,
            dialog = dialog,
            snack = snack,
            coach = coachVisible && mode == Mode.PLACING && !settings.coachSeen,
            trackingLost = trackingLost,
            language = settings.language,
            hints = settings.hints,
            scenarioPref = settings.scenario,
            restoring = restoring,
            floorY = floorY,
            floorOutline = floorOutline,
            room = room,
            roomSource = roomSource,
            ceilingScanned = realCeilingY != null,
            roomEdit = roomEdit && (mode == Mode.HOME || mode == Mode.PLACING),
            muted = settings.muted,
            listOpen = listOpen && !roomEdit && (mode == Mode.HOME || mode == Mode.PLACING),
            headAt = if (listOpen) head?.let { Vec3(Math.round(it.x * 10f) / 10f, 0f, Math.round(it.z * 10f) / 10f) } else null,
        )
    }

    private fun publish() {
        val s = buildState()
        _state.value = s
        if (s.stateName != lastStateName) {
            lastStateName = s.stateName
            FireLog.i("STATE ${s.stateName} mode=${s.mode} phase=${s.drill?.phase ?: "-"}")
        }
    }

    /** The whole model as one JSON line (debug `dump`). */
    fun dumpJson(): String {
        val s = buildState()
        val o = JSONObject()
        o.put("state", s.stateName)
        o.put("mode", s.mode.name)
        o.put("markers", JSONArray().apply { s.markers.forEach { m -> put(JSONObject().put("id", m.id).put("kind", m.kind.tag).put("x", m.position.x.toDouble()).put("y", m.position.y.toDouble()).put("z", m.position.z.toDouble()).put("saved", m.saved).put("exitIndex", m.exitIndex)) } })
        o.put("routes", JSONObject().apply { s.routes.forEach { (k, v) -> put(k, v.size) } })
        o.put("placeKind", s.placeKind?.tag)
        o.put("selected", s.selectedId)
        o.put("menu", s.menuOpen)
        o.put("dialog", s.dialog?.name)
        o.put("snack", s.snack?.kind?.name)
        o.put("coach", s.coach)
        o.put("trackingLost", s.trackingLost)
        o.put("language", s.language.tag)
        o.put("hints", s.hints)
        o.put("scenario", s.scenarioPref.tag)
        o.put("records", records.size)
        s.drill?.let { d ->
            o.put("drill", JSONObject().put("phase", d.phase.name).put("scenario", d.scenario.tag).put("elapsed", d.elapsedS.toDouble()).put("level", d.level).put("sweeps", d.sweeps).put("spraying", d.spraying).put("sprayLeft", d.sprayLeftS.toDouble()).put("distanceBad", d.distanceBad?.name).put("aim", d.aim.name).put("paused", d.paused).put("hint", d.hint.name).put("fireOut", d.fireOut))
        }
        s.result?.let { r -> o.put("result", JSONObject().put("total", r.totalS.toDouble()).put("findings", JSONArray(r.findings.map { it.name })).put("prev", r.prevS?.toDouble())) }
        s.routeRec?.let { r -> o.put("routeRec", JSONObject().put("phase", r.phase.name).put("walked", r.walkedM.toDouble()).put("points", r.points.size)) }
        room?.let { r -> o.put("room", JSONObject().put("source", roomSource?.name).put("w", r.width.toDouble()).put("l", r.length.toDouble()).put("h", r.height.toDouble()).put("floorY", r.floorY.toDouble()).put("yaw", r.yawDeg.toDouble()).put("cx", r.center.x.toDouble()).put("cz", r.center.z.toDouble()).put("edit", roomEdit)) }
        o.put("muted", settings.muted)
        o.put("list", s.listOpen)
        head?.let { h -> o.put("head", JSONArray().put(h.x.toDouble()).put(h.y.toDouble()).put(h.z.toDouble())) }
        return o.toString()
    }

    override fun onCleared() {
        tickJob?.cancel()
        restoreJob?.cancel()
        snackJob?.cancel()
        scanJob?.cancel()
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val TICK_MS = 100L
        private const val SMOOTH_N = 3
        private const val RESTORE_TIMEOUT_MS = 10_000L
        private const val RESTORE_RETRY_MS = 2_000L
        private const val BLOCKED_GRACE_MS = 2_000L
        private const val SCAN_TIMEOUT_MS = 5_000L
        private const val ADOPT_EPS_M = 0.02f
        private const val OUTSIDE_MAX_M = 12f
        private const val FRONT_M = 1.5f
        private const val MOVE_TAP_GRACE_MS = 400L
        private val DRILL_STATES = setOf("S11", "S12", "S13", "S14", "S15", "S15+M1", "S16", "S17", "S19", "M1")

        fun horizontal(a: Vec3, b: Vec3): Float = hypot(a.x - b.x, a.z - b.z)
    }
}
