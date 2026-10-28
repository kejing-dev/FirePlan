package io.embodit.fireplan.debug

import android.content.Context
import io.embodit.fireplan.domain.model.LanguagePref
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.Scenario
import io.embodit.fireplan.domain.model.ScenarioPref
import io.embodit.fireplan.domain.model.RoomBox
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.domain.usecase.HitSurfaceUseCase
import io.embodit.fireplan.platform.FireGraph
import io.embodit.fireplan.platform.FireLog
import io.embodit.fireplan.ui.fire.AimTarget
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.debug.FireProbe
import java.io.File
import java.util.Locale

/**
 * QA hooks of the debug build (design `qaHooks`). One log line (tag `FirePlan`) answers every command.
 *
 * - `seed`                      the example setup: fire spot on a table, one extinguisher 4 m away, two exits,
 *                               two detectors (one nearer than 3 m to the stove) and one route; one past drill
 * - `state <Sxx|Mx>`            straight to a designed state (S00 - S23, S15+M1, M1, M2); `state S05 det` selects
 *                               the near detector instead of the extinguisher, `state S05 det2` the far one
 * - `place <fire|ext|exit|det> [x,y,z]`  a marker at a Stage point (snapped to a fitting surface), or at a default spot
 * - `drill small|big`           a drill with that scenario, countdown skipped
 * - `arrive <fire|ext|exit|exit2|id>`   as if the user had walked to that marker
 * - `pass`                      pull the pin (S13 -> S14)
 * - `aim ring|top|none`         what the pointer aims at (S14 variants)
 * - `spray on|off`              squeeze / release while aiming at the base
 * - `sweep <n>`                 sweep count injected (3 puts the fire out)
 * - `distance near|far|ok`      the distance check forced (M1)
 * - `pause` / `resume` / `end`  the drill
 * - `coach_reset`               the first-run coachmark shows again
 * - `restore_timeout`           the "your last setup isn't here" dialog (S10)
 * - `tracking lost|ok`          the head tracker reports lost tracking / its real state (M2)
 * - `lang system|zh|en`, `hints on|off`, `scenario random|small|big`
 * - `menu`, `clear` (the dialog), `clear_now` (clears at once), `exit`
 * - `ui <control>`              presses a panel control by name (home_setup, home_drill, home_result, more, back,
 *                               tile_fire, tile_exit, tile_ext, tile_det, tray_route, pick_exit1, pick_exit2,
 *                               route_stop, route_redo, route_finish, pause, pull_pin, pause_resume, pause_restart,
 *                               pause_end, result_again, result_done, strip_delete, strip_close, menu_clear,
 *                               menu_exit, lang_*, hints_*, scen_*, dialog_cancel, dialog_confirm, dialog_retry,
 *                               dialog_redo, dialog_exit, coach_got_it, snackbar_action)
 * - `dump`                      the whole model as one JSON line (`DUMP {...}`), also written to files/dump.json
 * - `pose`, `bounds`, `planes`, `park`, `fps on|off`
 *
 * v0.1.1 (the room box, taste profile D7):
 * - `noscan on|off`             ignore plane detection (the nothing-detected path); `scan_timeout` fires the 5 s timeout now
 * - `room`                      opens the room card; `room w+|w-|l+|l-|h+|h-|turn+|turn-|left|right|away|closer|recenter|done`
 * - `tap <x,y,z>`               a pinch on the room / table surface nearest to that Stage point (as the system pointer would)
 * - `platetest on|off`         every marker plate shows an asymmetric "F" + up arrow (texture orientation check, v0.1.2)
 * - `mute` (toggles), `list` / `list off`, `tofront <marker id>`
 * - `ray ox,oy,oz,dx,dy,dz` / `ray off` and `trigger on|off`: a stand-in controller ray and trigger (preview ghost,
 *   pick a plate and drag it, release = put down), for the emulator's eye mode that has no controller
 * - `ui <control>` also knows room, tray_room, room_back, room_recenter, room_w_minus / _plus, room_l_*, room_h_*,
 *   room_turn_a / _b, room_side_a / _b, room_depth_a / _b, route_auto, snackbar_room
 */
internal object DebugCommands {

    fun run(context: Context, raw: String, a: String?, s: String?) {
        val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val name = tokens.first().lowercase(Locale.US)
        val rest = tokens.drop(1)
        val numbers = floats(a ?: rest.joinToString(","))
        val text = (s ?: rest.joinToString(" ")).trim()
        val host = FireProbe.host
        if (host == null) {
            FireLog.i("CMD $name ignored: the Stage is not running")
            return
        }
        try {
            dispatch(context, host, name, numbers, text)
        } catch (t: Throwable) {
            FireLog.e("CMD $name failed: $t", t)
        }
    }

    private fun dispatch(context: Context, host: FireProbe.Host, name: String, numbers: List<Float>, text: String) {
        val scene = host.scene()
        val word = text.lowercase(Locale.US)
        when (name) {
            "seed" -> host.dispatch(FireEvent.DebugSeed)
            "state" -> host.dispatch(FireEvent.DebugState(text))
            "place" -> {
                val parts = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                val kind = MarkerKind.fromTag(parts.firstOrNull()?.lowercase(Locale.US)) ?: return FireLog.i("CMD place needs fire|ext|exit|det")
                val nums = parts.drop(1).mapNotNull { it.toFloatOrNull() }
                host.dispatch(FireEvent.DebugPlace(kind, if (nums.size >= 3) Vec3(nums[0], nums[1], nums[2]) else null))
            }
            "drill" -> host.dispatch(FireEvent.DebugDrill(if (word == "big") Scenario.BIG else Scenario.SMALL))
            "arrive" -> host.dispatch(FireEvent.DebugArrive(word))
            "pass", "pull" -> host.dispatch(FireEvent.DebugPass)
            "aim" ->
                host.dispatch(
                    FireEvent.DebugAim(
                        when (word) {
                            "ring", "base" -> AimTarget.RING
                            "top", "flame" -> AimTarget.TOP
                            else -> AimTarget.NONE
                        }
                    )
                )
            "spray" -> host.dispatch(FireEvent.DebugSpray(word != "off" && word != "stop"))
            "sweep" -> host.dispatch(FireEvent.DebugSweep(numbers.firstOrNull()?.toInt() ?: 1))
            "distance" -> host.dispatch(FireEvent.DebugDistance(word))
            "pause" -> host.dispatch(FireEvent.PauseClicked)
            "resume" -> host.dispatch(FireEvent.ResumeClicked)
            "end" -> host.dispatch(FireEvent.EndDrillClicked)
            "coach_reset", "coach" -> host.dispatch(FireEvent.DebugCoachReset)
            "restore_timeout" -> host.dispatch(FireEvent.DebugRestoreTimeout)
            "tracking" -> host.dispatch(FireEvent.DebugTracking(if (word == "lost") true else if (word == "ok") false else null))
            "lang" -> host.dispatch(FireEvent.LanguageSelected(LanguagePref.fromTag(word)))
            "hints" -> host.dispatch(FireEvent.HintsSelected(word == "on"))
            "scenario" -> host.dispatch(FireEvent.ScenarioSelected(ScenarioPref.entries.firstOrNull { it.tag == word } ?: ScenarioPref.RANDOM))
            "menu" -> host.dispatch(FireEvent.MoreClicked)
            "clear" -> host.dispatch(FireEvent.ClearAllClicked)
            "clear_now" -> host.dispatch(FireEvent.DebugClear)
            "exit" -> host.dispatch(FireEvent.ExitClicked)
            "ui" -> FireLog.i("UI $text pressed=${host.pressControl(text)}")
            "platetest" -> FireLog.i(scene?.setPlateTest(word != "off") ?: NO_SCENE)
            "mute" -> host.dispatch(FireEvent.MuteToggled)
            "list" -> host.dispatch(if (word == "off" || word == "close") FireEvent.ListClosed else FireEvent.ListClicked)
            "tofront" -> host.dispatch(FireEvent.MarkerToFront(word))
            "ray" -> {
                // `ray ox,oy,oz,dx,dy,dz` stands in for the controller ray; `ray off` gives it back
                val r = if (word == "off" || numbers.size < 6) scene?.setDebugRay(null, null, null) else scene?.setDebugRay(Vec3(numbers[0], numbers[1], numbers[2]), Vec3(numbers[3], numbers[4], numbers[5]), null)
                FireLog.i(r ?: NO_SCENE)
            }
            "trigger" -> FireLog.i(scene?.setDebugRay(null, Vec3.ZERO, word != "off" && word != "up") ?: NO_SCENE)
            "noscan" -> host.dispatch(FireEvent.DebugNoScan(word != "off"))
            "scan_timeout" -> host.dispatch(FireEvent.DebugScanTimeout)
            "room" -> {
                val w = RoomBox.SIZE_STEP_M
                val h = RoomBox.HEIGHT_STEP_M
                val m = RoomBox.MOVE_STEP_M
                val t = RoomBox.TURN_STEP_DEG
                host.dispatch(
                    when (word) {
                        "", "edit", "open" -> FireEvent.RoomClicked
                        "w+" -> FireEvent.RoomResized(dw = w)
                        "w-" -> FireEvent.RoomResized(dw = -w)
                        "l+" -> FireEvent.RoomResized(dl = w)
                        "l-" -> FireEvent.RoomResized(dl = -w)
                        "h+" -> FireEvent.RoomResized(dh = h)
                        "h-" -> FireEvent.RoomResized(dh = -h)
                        "turn+" -> FireEvent.RoomMoved(turnDeg = t)
                        "turn-" -> FireEvent.RoomMoved(turnDeg = -t)
                        "left" -> FireEvent.RoomMoved(right = -m)
                        "right" -> FireEvent.RoomMoved(right = m)
                        "away" -> FireEvent.RoomMoved(ahead = m)
                        "closer" -> FireEvent.RoomMoved(ahead = -m)
                        "recenter" -> FireEvent.RoomRecentered
                        else -> FireEvent.RoomDoneClicked
                    }
                )
            }
            "tap" -> {
                if (numbers.size < 3) return FireLog.i("CMD tap needs x,y,z")
                val p = Vec3(numbers[0], numbers[1], numbers[2])
                val hits = HitSurfaceUseCase()
                val plane = FireGraph.planes.all.filter { hits.contains(it, p, margin = 0.05f) }.minByOrNull { hits.distanceToPlane(it, p) }
                if (plane == null || hits.distanceToPlane(plane, p) > 0.1f) return FireLog.i("CMD tap: no surface at ${FireLog.pos(p)}")
                FireLog.i("TAP surface plane=${plane.label} kind=debug stage=${FireLog.pos(p)}")
                host.dispatch(FireEvent.SurfaceTapped(plane.id, hits.project(plane, p)))
            }
            "dump" -> {
                val json = host.dumpJson()
                FireLog.i("DUMP $json")
                runCatching { File(context.filesDir, "dump.json").writeText(json) }
            }
            "pose" -> FireLog.i(scene?.poseLine() ?: NO_SCENE)
            "bounds" -> {
                scene?.panelLines()?.forEach { FireLog.i(it) } ?: FireLog.i(NO_SCENE)
                for (panel in listOf("mainPanel", "drillCard", "resultPanel", "markerStrip", "detectorCard", "pauseMenu", "moreMenu", "dialogPanel", "snackPanel", "coachPanel")) {
                    for ((ctrl, b) in FireProbe.controlsOf(panel)) {
                        FireLog.i(String.format(Locale.US, "CTRL panel=%s name=%s cx=%.1f cy=%.1f w=%.1f h=%.1f", panel, ctrl, b.cx, b.cy, b.w, b.h))
                    }
                }
            }
            "planes" -> scene?.planesSummary()?.forEach { FireLog.i(it) } ?: FireLog.i(NO_SCENE)
            "park" -> FireLog.i(scene?.parkPanel() ?: NO_SCENE)
            "fps" -> FireLog.i(scene?.setFpsLine(word == "on") ?: NO_SCENE)
            else -> FireLog.i("CMD unknown '$name'")
        }
    }

    private fun floats(text: String): List<Float> = text.split(Regex("[\\s,]+")).mapNotNull { it.trim().toFloatOrNull() }

    private const val NO_SCENE = "CMD ignored: no scene"
}
