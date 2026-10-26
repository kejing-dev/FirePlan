package io.embodit.fireplan.ui.fire.scene

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.PointerRay
import io.embodit.fireplan.domain.model.SurfaceKind
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.domain.usecase.PlaceMarkerUseCase
import io.embodit.fireplan.platform.FireGraph
import io.embodit.fireplan.platform.FireLog
import io.embodit.fireplan.platform.audio.AlarmPlayer
import io.embodit.fireplan.platform.sense.PlaneTracker
import io.embodit.fireplan.platform.tracking.HeadPose
import io.embodit.fireplan.domain.model.Quat4
import io.embodit.fireplan.ui.fire.AimTarget
import io.embodit.fireplan.ui.fire.DrillPhase
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.Mode
import io.embodit.fireplan.ui.fire.debug.FireProbe
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: FireScene? = null
}

/** The one per-frame ECS system of the app. */
class FireFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/**
 * The 3D side of the app: everything that lives in the room (markers, route dots, flame, base ring,
 * extinguisher prop, spray cone, alarm source), the tap targets, and the placement of the pre-built
 * panels. Compose state is rendered through [render] (idempotent diff); per-frame work happens in
 * [onFrame] only and never goes through Compose (the head is reported to the ViewModel ten times a
 * second). Everything started in [attach] is stopped in [detach]. Main thread only.
 */
class FireScene(private val sink: (FireEvent) -> Unit) : FireProbe.SceneProbe {

    /** Panel id -> the panel is in view: a pooled panel composes its content only then. */
    val panelShown = mutableStateMapOf<String, Boolean>()

    /** Share of its panel's width that a panel's content fills (snackbar capsule). */
    val panelStretch = mutableStateMapOf<String, Float>()

    /** Bumped to make `SpatialView.update` run again while panel entities are still missing. */
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val planeTracker = FireGraph.planes
    private val headTracker = FireGraph.head
    private val controller = FireGraph.controller
    private val alarm = AlarmPlayer()

    private var content: SpatialViewContent? = null
    private var stageRoot: Entity? = null
    private var contentProbe: Entity? = null
    private var materials: SceneMaterials? = null
    private var shared: SharedResources? = null
    private var colliders: PlaneColliders? = null
    private var rig: PanelRig? = null
    private var markers: MarkerViews? = null
    private var flame: FlameView? = null
    private var prop: ExtinguisherView? = null
    private var routes: RouteView? = null
    private var alarmSource: Entity? = null
    private var attached = false
    private var attachedAtMs = 0L

    private val mainFollower = Follower("main", Follower.MAIN_OFFSET, MAIN_HALF_EXTENTS, Follower.MAIN_LEAN)
    private val propFollower = Follower("prop", Follower.PROP_OFFSET, ExtinguisherView.HALF_EXTENTS, nearM = 0.25f, farM = 0.7f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var buildJob: Job? = null

    private var ui = FireUiState()
    private var head = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var lastHeadSentMs = 0L
    private var lastPlaneCount = -1
    private var lastPlanesEmitMs = 0L
    private var lastFloor = false
    private var lastCeiling = false
    private var lastLost = false
    private var panelPressed = false
    private var lastPanelPressMs = NEVER
    private var dialogPlaced = false

    // spraying: the system drag or the controller trigger moves the aim sideways across the base ring
    private var dragSpray = false
    private var dragPxToM = 0.001f
    private var sprayLateral = 0f
    private var ctrlSpray = false
    private var ctrlAim = AimTarget.NONE
    private var ctrlWasPressed = false
    private var alarmKey = ""
    private var alarmLow = false
    private var alarmPaused = false
    private var extinguished = false
    private var fpsOn = false
    private var frames = 0
    private var fpsStartMs = 0L

    /** The main panel's pointer: a press holds the lazy follow still. */
    fun onPanelPointer(pressed: Boolean) {
        panelPressed = pressed
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        attachedAtMs = t0
        this.content = content
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // An entity added to the SpatialView is not at the Stage origin (0.256 m off in z for a
        // full-size view). Move the root once so that its local space equals Stage space.
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        FireLog.i("STAGE root offset=${FireLog.pos(offset.domain())}")

        val rig = PanelRig(root) { id, shown -> panelShown[id] = shown }
        this.rig = rig
        for (id in PanelSpec.pool) {
            val (w, h) = PanelSpec.size(id)
            if (id == PanelSpec.SNACK) rig.head(id, SNACK_HEAD_OFFSET, PanelSpec.FLOATING_SCALE, w, h) else rig.world(id, w, h)
        }
        headTracker.start()
        controller.start()
        planeTracker.start(
            PlaneTracker.Listener { id, surface, _ ->
                if (surface != null) colliders?.upsert(surface) else colliders?.remove(id)
            }
        )
        ActiveScene.scene = this
        bindPanels(attachments)
        mainFollower.reset()
        propFollower.reset()
        buildJob =
            scope.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) FireLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
    }

    /** Materials and shared meshes; the main thread is given back after every mesh (a mesh costs about a frame). */
    private suspend fun build(root: Entity, t0: Long) {
        val sh = SharedResources()
        shared = sh
        val m = SceneMaterials(sh)
        materials = m
        yield()
        colliders =
            PlaneColliders(root).also {
                it.buildBackstop()
                planeTracker.all.forEach(it::upsert)
            }
        yield()
        markers = MarkerViews(root, m, sh, FireGraph.appContext).also { it.prepare() }
        yield()
        routes = RouteView(root, m, sh).also { it.prepare() }
        yield()
        flame = FlameView(root, m, sh).also { it.prepare() }
        yield()
        prop = ExtinguisherView(root, m, sh).also { it.prepare() }
        yield()
        alarmSource = Entity().named("alarmSource").also { root.addChild(it) }
        FireGraph.anchors.start()
        attached = true
        FireLog.i("SCENE attached build_ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui, force = true)
        sink(FireEvent.SceneReady)
    }

    /** Called from `SpatialView(update = ...)`: parents freshly composed panel entities under their holders. */
    fun bindPanels(attachments: SpatialViewAttachments) {
        val rig = rig ?: return
        val complete = rig.bind(attachments, PanelSpec.pool, SystemClock.elapsedRealtime())
        if (!complete) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, REBIND_DELAY_MS)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        buildJob?.cancel()
        buildJob = null
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        alarm.stop()
        planeTracker.stop()
        headTracker.stop()
        controller.stop()
        FireGraph.anchors.stop()
        panelShown.clear()
        panelStretch.clear()
        runCatching { rig?.release() }
        markers?.clear()
        routes?.clear()
        flame?.destroy()
        prop?.destroy()
        colliders?.clear()
        runCatching { alarmSource?.destroy(true) }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        stageRoot = null
        contentProbe = null
        content = null
        materials = null
        colliders = null
        rig = null
        markers = null
        flame = null
        prop = null
        routes = null
        alarmSource = null
        hasHead = false
        lastPlaneCount = -1
        lastLost = false
        alarmKey = ""
        extinguished = false
        dialogPlaced = false
        FireLog.i("SCENE detached")
    }

    // =========================================================================================
    // model -> entities (called when the UI state changes)
    // =========================================================================================

    fun render(state: FireUiState, force: Boolean = false) {
        val previous = ui
        ui = state
        if (!attached) return
        val now = SystemClock.elapsedRealtime()
        if (force || previous.markers !== state.markers || previous.selectedId != state.selectedId) {
            markers?.sync(state.markers, if (state.mode == Mode.PLACING) state.selectedId else null)
        }
        if (force || previous.routes !== state.routes || previous.routeRec?.points !== state.routeRec?.points || previous.floorY != state.floorY) {
            val all = LinkedHashMap(state.routes)
            val rec = state.routeRec
            if (rec != null && rec.points.isNotEmpty()) all["recording"] = rec.points
            routes?.sync(all, state.floorY)
        }
        val routesVisible =
            state.mode == Mode.PLACING || state.mode == Mode.ROUTE_PICK || state.mode == Mode.ROUTE_RECORDING || state.mode == Mode.ROUTE_DONE ||
                (state.mode == Mode.DRILL && (state.drill?.phase == DrillPhase.OUT || state.drill?.phase == DrillPhase.EVACUATE))
        routes?.setVisible(routesVisible)
        syncDrill(state, now)
        syncPanels(state)
    }

    private fun syncDrill(state: FireUiState, now: Long) {
        val flame = flame ?: return
        val prop = prop ?: return
        val d = state.drill
        val fireMarker = state.markers.firstOrNull { it.kind == MarkerKind.FIRE }
        if (state.mode != Mode.DRILL || d == null || d.phase == DrillPhase.COUNTDOWN || fireMarker == null) {
            flame.hide()
            prop.setShown(false, now)
            prop.spray(null)
            stopAlarm()
            extinguished = false
            routes?.unlightAll()
            state.markers.filter { it.kind == MarkerKind.EXIT }.forEach { markers?.setEmphasis(it.id, 1f) }
            alarmKey = ""
            return
        }
        // the flame
        if (d.fireOut) {
            flame.extinguish(now)
            extinguished = true
        } else {
            val firstShow = alarmKey.isEmpty()
            flame.show(fireMarker.position, d.level, now, immediate = firstShow)
        }
        flame.setFrozen(d.paused, now)
        // the prop and the base ring (S13 - S15)
        val holding = d.phase == DrillPhase.GOT || d.phase == DrillPhase.AIM || d.phase == DrillPhase.SWEEP
        prop.setShown(holding, now)
        prop.setPinHighlight(d.phase == DrillPhase.GOT)
        prop.setPressure(d.sprayLeftS / io.embodit.fireplan.domain.model.DrillRules.SPRAY_S)
        flame.setRing(holding && !d.fireOut)
        flame.setRingStyle(
            when {
                d.distanceBad != null && d.phase == DrillPhase.SWEEP -> FlameView.RingStyle.ALERT
                d.aim == AimTarget.RING || d.spraying -> FlameView.RingStyle.SOLID
                else -> FlameView.RingStyle.DASHED
            }
        )
        if (!d.spraying) {
            prop.spray(null)
            sprayLateral = 0f
        }
        // the alarm: from the nearest detector, else from the fire; low once the user acts; silent when out
        val key = "${d.scenario}:${fireMarker.id}"
        if (d.fireOut) {
            stopAlarm()
        } else {
            if (alarmKey != key) {
                alarmKey = key
                val at = d.alarmMarkerId?.let { id -> markers?.position(id) } ?: fireMarker.position
                alarmSource?.setLocal(position = at)
                alarmSource?.let { alarm.start(it) }
                alarmLow = false
                alarmPaused = false
            }
            val low = d.phase != DrillPhase.FIRE
            if (low != alarmLow) {
                alarmLow = low
                alarm.setVolume(if (low) io.embodit.fireplan.domain.model.DrillRules.ALARM_LOW_VOLUME else 1f)
            }
            if (d.paused != alarmPaused) {
                alarmPaused = d.paused
                if (d.paused) alarm.pause() else alarm.resume()
            }
        }
    }

    private fun stopAlarm() {
        if (alarm.playing || alarmKey.isNotEmpty()) alarm.stop()
    }

    private fun syncPanels(state: FireUiState) {
        val rig = rig ?: return
        val drillCard = state.mode == Mode.DRILL
        val result = state.mode == Mode.RESULT
        if (!drillCard) rig[PanelSpec.DRILL]?.hide()
        if (!result) rig[PanelSpec.RESULT]?.hide()
        if (drillCard || result) rig[PanelSpec.MAIN]?.hide()
        // the family is placed by the follower every frame (placeMainFamily)
        if (!state.menuOpen) rig[PanelSpec.MENU]?.hide()
        if (!(state.drill?.paused == true)) rig[PanelSpec.PAUSE]?.hide()
        if (!(state.coach && state.mode == Mode.PLACING && !state.menuOpen)) rig[PanelSpec.COACH]?.hide()
        val strip = state.mode == Mode.PLACING && state.selectedId != null
        if (!strip) rig[PanelSpec.STRIP]?.hide()
        val card = strip && state.detector != null
        if (!card) rig[PanelSpec.DETECTOR]?.hide()
        val dialog = rig[PanelSpec.DIALOG]
        if (state.dialog != null) {
            if (!dialogPlaced) {
                val flat = Vec3(head.forward.x, 0f, head.forward.z).let { if (it.length < 0.1f) Vec3.AHEAD else it.normalized() }
                dialog?.show(head.position + flat * PanelSpec.FLOATING_DISTANCE_M, PanelSpec.FLOATING_SCALE)
                dialogPlaced = true
            }
        } else {
            dialog?.hide()
            dialogPlaced = false
        }
        val snack = rig[PanelSpec.SNACK]
        if (state.snack != null) snack?.show(null, null) else snack?.hide()
    }

    /** Width of the snackbar's capsule, dp; called by the panel. */
    fun reportSnack(widthDp: Float) {
        val slot = rig?.get(PanelSpec.SNACK) ?: return
        if (widthDp <= 0f) return
        slot.widthFactor = widthDp / slot.widthDp
        if (panelStretch[PanelSpec.SNACK] != slot.widthFactor) panelStretch[PanelSpec.SNACK] = slot.widthFactor
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        headTracker.pose()?.let {
            head = it
            hasHead = true
        }
        val rig = rig ?: return
        rig.onFrame(now)
        rig.stowBehind(head.view)
        val state = ui
        val hold = panelPressed || (lastPanelPressMs != NEVER && now - lastPanelPressMs < PANEL_HOLD_MS) || state.menuOpen || state.dialog != null || state.drill?.paused == true
        if (hasHead && head.position.length > 1e-3f) {
            mainFollower.follow(head, planeTracker.all, planeTracker.count, now, hold)
            propFollower.follow(head, planeTracker.all, planeTracker.count, now, hold = false)
        }
        placeMainFamily(state, rig)
        countFrame(now)
        if (!attached) return

        pollTracking(now)
        pollPlanes(now)
        markers?.onFrame(head.position)
        prop?.place(propFollower.pose)
        prop?.onFrame(now)
        flame?.onFrame(now)
        drillFrame(state, now)
        placeStrips(state, rig)
        sampleController(state, now)
        if (hasHead && now - lastHeadSentMs >= HEAD_SAMPLE_MS) {
            lastHeadSentMs = now
            sink(FireEvent.HeadSample(head.position, head.forward))
        }
        rig.faceViewer(head.position)
    }

    /** The main panel family (main / drill card / result) and the menus beside it ride on the lazy follow. */
    private fun placeMainFamily(state: FireUiState, rig: PanelRig) {
        if (!hasHead) return
        val pose = mainFollower.pose
        val rot = mainFollower.rotation
        val k = PanelSpec.MAIN_SCALE
        val shownId =
            when (state.mode) {
                Mode.DRILL -> PanelSpec.DRILL
                Mode.RESULT -> PanelSpec.RESULT
                else -> PanelSpec.MAIN
            }
        rig[shownId]?.place(pose.position, rot, k)
        val (hw, hh) = PanelSpec.halfM(shownId, k)
        if (state.menuOpen) {
            val (mw, mh) = PanelSpec.halfM(PanelSpec.MENU, k)
            rig[PanelSpec.MENU]?.place(pose.position + rot.rotate(Vec3(hw - mw, hh + 0.012f + mh, 0.012f)), rot, k)
        }
        if (state.drill?.paused == true && state.mode == Mode.DRILL) {
            val (pw, ph) = PanelSpec.halfM(PanelSpec.PAUSE, k)
            rig[PanelSpec.PAUSE]?.place(pose.position + rot.rotate(Vec3(pw - hw, hh + 0.012f + ph, 0.012f)), rot, k)
        }
        if (state.coach && state.mode == Mode.PLACING && !state.menuOpen) {
            val (cw, ch) = PanelSpec.halfM(PanelSpec.COACH, k)
            rig[PanelSpec.COACH]?.place(pose.position + rot.rotate(Vec3(hw + COACH_GAP_M + cw, ch - hh, 0f)), rot, k)
        }
    }

    /** Marker strip above the selected marker's plate; detector card below a selected detector. */
    private fun placeStrips(state: FireUiState, rig: PanelRig) {
        if (state.mode != Mode.PLACING) return
        val id = state.selectedId ?: return
        val plate = markers?.platePosition(id) ?: return
        val k = PanelSpec.MAIN_SCALE
        val (_, sh) = PanelSpec.halfM(PanelSpec.STRIP, k)
        rig[PanelSpec.STRIP]?.show(plate + Vec3(0f, MarkerViews.PLATE_R + STRIP_GAP_M + sh, 0f), k)
        if (state.detector != null) {
            val (_, dh) = PanelSpec.halfM(PanelSpec.DETECTOR, k)
            rig[PanelSpec.DETECTOR]?.show(plate - Vec3(0f, MarkerViews.PLATE_R + CARD_GAP_M + dh, 0f), k)
        }
    }

    /** Spray cone, route lighting, alarm blink, exit emphasis. */
    private fun drillFrame(state: FireUiState, now: Long) {
        val d = state.drill
        val flame = flame ?: return
        if (state.mode != Mode.DRILL || d == null) return
        markers?.setAlarm(if (!d.fireOut && !d.paused) d.alarmMarkerId else null, now)
        if (d.spraying) {
            val target = flame.position + sprayTangent() * sprayLateral + Vec3(0f, 0.05f, 0f)
            prop?.spray(target)
        }
        if (d.phase == DrillPhase.OUT || d.phase == DrillPhase.EVACUATE) {
            val r = routes
            val foot = Vec3(head.position.x, state.floorY, head.position.z)
            val exits = state.markers.filter { it.kind == MarkerKind.EXIT }
            if (r != null && !r.isEmpty) {
                val near = r.nearest(foot)
                if (near != null) {
                    r.light(near.first, near.second, now)
                    for (e in exits) markers?.setEmphasis(e.id, if (e.id == near.first) 1.2f else 1f)
                }
            } else {
                // no route recorded: the exits pulse instead
                val p = MarkerViews.pulse(now)
                for (e in exits) markers?.setEmphasis(e.id, p)
            }
        }
    }

    /** Horizontal unit vector across the line of sight to the fire (the sweep direction). */
    private fun sprayTangent(): Vec3 {
        val f = flame?.position ?: return Vec3.RIGHT
        var to = Vec3(f.x - head.position.x, 0f, f.z - head.position.z)
        if (to.length < 1e-3f) to = Vec3.AHEAD
        return Vec3.UP.cross(to.normalized()).normalized()
    }

    /** Controller ray: aiming at the base ring / the flames, trigger = squeeze, moving the ray = sweep. */
    private fun sampleController(state: FireUiState, now: Long) {
        val d = state.drill
        val flame = flame ?: return
        val holding = state.mode == Mode.DRILL && d != null && (d.phase == DrillPhase.GOT || d.phase == DrillPhase.AIM || d.phase == DrillPhase.SWEEP)
        val sample = controller.sample()
        if (sample == null || !holding || d == null) {
            if (ctrlAim != AimTarget.NONE) {
                ctrlAim = AimTarget.NONE
                if (holding) sink(FireEvent.AimChanged(AimTarget.NONE))
            }
            if (ctrlSpray) {
                ctrlSpray = false
                sink(FireEvent.SprayEnded)
            }
            ctrlWasPressed = sample?.pressed ?: false
            return
        }
        val aim = flame.aimOf(sample.ray)
        if (aim != ctrlAim) {
            ctrlAim = aim
            sink(FireEvent.AimChanged(aim))
        }
        val pressed = sample.pressed || sample.trigger > 0.6f
        if (pressed && !ctrlWasPressed && d.phase == DrillPhase.AIM && aim == AimTarget.RING && !dragSpray) {
            ctrlSpray = true
            sprayLateral = 0f
            sink(FireEvent.SprayStarted(onRing = true))
        }
        if (ctrlSpray) {
            if (pressed) {
                sprayLateral = flame.lateralOf(sample.ray, sprayTangent())
                sink(FireEvent.SprayMoved(sprayLateral))
            } else {
                ctrlSpray = false
                sink(FireEvent.SprayEnded)
            }
        }
        ctrlWasPressed = pressed
    }

    private fun pollPlanes(now: Long) {
        val count = planeTracker.count
        val planes = planeTracker.all
        val hasFloor = planes.any { it.kind == SurfaceKind.FLOOR }
        val hasCeiling = PlaceMarkerUseCase.hasCeiling(planes)
        if (count == lastPlaneCount && hasFloor == lastFloor && hasCeiling == lastCeiling) return
        if (now - lastPlanesEmitMs < PLANES_EMIT_MS) return
        lastPlanesEmitMs = now
        lastPlaneCount = count
        lastFloor = hasFloor
        lastCeiling = hasCeiling
        val floorY = PlaceMarkerUseCase.floorHeight(planes) ?: 0f
        val outline = planes.filter { it.kind == SurfaceKind.FLOOR }.maxByOrNull { it.vertices.size }?.vertices ?: emptyList()
        FireLog.i("PLANES n=$count floor=$hasFloor ceiling=$hasCeiling floorY=${FireLog.f(floorY)}")
        sink(FireEvent.PlanesChanged(count, hasFloor, floorY, hasCeiling, outline))
    }

    private fun pollTracking(now: Long) {
        val lost = headTracker.lost(now)
        if (lost == lastLost) return
        lastLost = lost
        sink(FireEvent.TrackingChanged(lost))
    }

    private fun countFrame(now: Long) {
        if (!fpsOn) return
        frames++
        if (fpsStartMs == 0L) fpsStartMs = now
        if (now - fpsStartMs >= 1000L) {
            FireLog.i("FPS app=${frames * 1000f / (now - fpsStartMs)} frames=$frames ms=${now - fpsStartMs}")
            frames = 0
            fpsStartMs = now
        }
    }

    // =========================================================================================
    // system pointer (gaze + pinch, emulator mouse)
    // =========================================================================================

    /** A system spatial tap on one of our entities. */
    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val stage = toStage(value.position.x, value.position.y, value.position.z)
        val kind = value.interactionKind
        if (target == null) {
            FireLog.i("TAP other target=none kind=$kind")
            return
        }
        val markerId = markers?.markerIdOf(target)
        val planeId = colliders?.planeIdOf(target)
        val flame = flame
        when {
            markerId != null -> {
                FireLog.i("TAP marker id=$markerId kind=$kind stage=${FireLog.pos(stage)}")
                sink(FireEvent.MarkerTapped(markerId))
            }
            prop?.isPin(target) == true -> {
                FireLog.i("TAP pin kind=$kind")
                sink(FireEvent.PinRingTapped)
            }
            flame != null && (flame.isBase(target) || flame.isTop(target)) -> {
                // a tap (no drag) on the flame while aiming only shows which part was aimed at
                FireLog.i("TAP flame part=${if (flame.isBase(target)) "base" else "top"} kind=$kind")
                sink(FireEvent.AimChanged(if (flame.isBase(target)) AimTarget.RING else AimTarget.TOP))
            }
            planeId != null && stage != null -> {
                FireLog.i("TAP surface plane=${name.removePrefix("plane:")} kind=$kind stage=${FireLog.pos(stage)}")
                sink(FireEvent.SurfaceTapped(planeId, stage))
            }
            colliders?.isBackstop(target) == true -> {
                FireLog.i("TAP backstop kind=$kind stage=${FireLog.pos(stage)}")
                sink(FireEvent.NothingTapped)
            }
            else -> FireLog.i("TAP other target=$name kind=$kind stage=${FireLog.pos(stage)}")
        }
    }

    /**
     * A system spatial drag: a press-and-hold on the base ring (or the flames) is the squeeze; the
     * hand's sideways travel (`dragAmount`, view pixels, 0.4 mm each at the target's depth) is the sweep.
     */
    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        val flame = flame ?: return
        when (phase) {
            "start" -> {
                val target = value.targetEntity ?: return
                val onBase = flame.isBase(target)
                val onTop = flame.isTop(target)
                if (!onBase && !onTop) return
                val d = ui.drill ?: return
                if (ui.mode != Mode.DRILL || (d.phase != DrillPhase.AIM && d.phase != DrillPhase.SWEEP && d.phase != DrillPhase.GOT)) return
                val dist = head.position.distanceTo(flame.position).coerceAtLeast(0.3f)
                dragPxToM = PX_M * (0.67f + 1.186f * dist)
                dragSpray = true
                sprayLateral = 0f
                FireLog.i("DRAG spray start on=${if (onBase) "base" else "top"} dist=${FireLog.f(dist)}")
                sink(FireEvent.SprayStarted(onRing = onBase))
            }
            "move" -> {
                if (!dragSpray) return
                val amount = value.dragAmount
                sprayLateral += amount.x * dragPxToM
                sink(FireEvent.SprayMoved(sprayLateral))
            }
            "end", "cancel" -> {
                if (!dragSpray) return
                dragSpray = false
                FireLog.i("DRAG spray end lateral=${FireLog.f(sprayLateral)}")
                sink(FireEvent.SprayEnded)
            }
        }
    }

    /** View-local pixels of a spatial tap -> Stage metres, through the SpatialView content conversion. */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .onFailure { FireLog.w("TAP convert failed: $it") }
            .getOrNull()
    }

    /** Head floor point (for the debug `pose`). */
    fun headPose(): HeadPose = head

    // =========================================================================================
    // debug probe
    // =========================================================================================

    override fun poseLine(): String =
        "POSE hmd pos=${FireLog.pos5(head.position)} ${FireLog.quat5(head.rotation)} fwd=${FireLog.pos5(head.forward)} valid=$hasHead"

    override fun panelLines(): List<String> {
        val rig = rig ?: return emptyList()
        return rig.all.filter { it.shown && it.panel != null }.map { slot ->
            String.format(Locale.US, "PANEL id=%s pos=%s %s scale=%.3f size_dp=%.0fx%.0f engine=%s", slot.id, FireLog.pos5(slot.position), FireLog.quat5(slot.rotation), slot.scale, slot.widthDp, slot.heightDp, FireLog.pos5(slot.enginePosition()))
        }
    }

    override fun planesSummary(): List<String> =
        planeTracker.all.map { p -> "PLANE ${p.label} kind=${p.kind} center=${FireLog.pos(p.centroid)} normal=${FireLog.pos(p.normal)} verts=${p.vertices.size}" }

    override fun parkPanel(): String {
        mainFollower.park(head, planeTracker.all, SystemClock.elapsedRealtime())
        return "FOLLOW main park requested"
    }

    override fun setFpsLine(on: Boolean): String {
        fpsOn = on
        frames = 0
        fpsStartMs = 0L
        return "FPS ${if (on) "on" else "off"}"
    }

    /** Planes for the ViewModel's placement rules (read on the main thread). */
    fun planes() = planeTracker.all

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val REBIND_DELAY_MS = 150L
        private const val PLANES_EMIT_MS = 500L
        private const val HEAD_SAMPLE_MS = 100L
        private const val PANEL_HOLD_MS = 600L
        private const val COACH_GAP_M = 0.03f
        private const val STRIP_GAP_M = 0.06f
        private const val CARD_GAP_M = 0.10f

        /** View pixels of a system drag -> metres at 1 m (0.4 mm each, times the depth factor). */
        private const val PX_M = 0.0004f

        /** Half sizes of the main panel at 0.75 (0.24 x 0.18 m) with a little air, for the surface rule. */
        private val MAIN_HALF_EXTENTS = listOf(0.13f, 0.10f, 0.01f)

        /** 14 degrees above the view centre, 0.9 m away: (0, sin14, -cos14) * 0.9. */
        private val SNACK_HEAD_OFFSET = Vec3(0f, 0.2177f, -0.8733f)
    }
}
