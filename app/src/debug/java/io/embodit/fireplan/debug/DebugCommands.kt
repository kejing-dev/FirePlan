package io.embodit.fireplan.debug

import android.content.Context
import io.embodit.fireplan.domain.model.LanguagePref
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.Scenario
import io.embodit.fireplan.domain.model.ScenarioPref
import io.embodit.fireplan.domain.model.Vec3
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
