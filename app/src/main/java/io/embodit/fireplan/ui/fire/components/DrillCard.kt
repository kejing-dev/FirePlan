package io.embodit.fireplan.ui.fire.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.fireplan.R
import io.embodit.fireplan.domain.model.DrillRules
import io.embodit.fireplan.ui.fire.DrillPhase
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.HintKind
import io.embodit.fireplan.ui.fire.scene.PanelSpec

/**
 * The drill card (280 x 120 dp at 0.75): the countdown digit or the running time (alert colour past
 * 2:00), the pause key, one line of text, and the "pull pin" button while the extinguisher is in hand.
 */
@Composable
internal fun DrillCard(state: FireUiState, filled: Boolean, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.DRILL_SIZE
    Glass(w.dp, h.dp, corner = 28.dp, padding = 12.dp) {
        if (!filled) return@Glass
        val d = state.drill ?: return@Glass
        val countdown = d.phase == DrillPhase.COUNTDOWN
        val over = !countdown && d.elapsedS >= DrillRules.TWO_MINUTES_S
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (countdown) d.countdown.coerceAtLeast(1).toString() else fmtTime(d.elapsedS),
                color = if (over) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.labelPrimary,
                style = FireType.timer,
                maxLines = 1,
                softWrap = false,
                textAlign = if (countdown) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            Key("pause", R.drawable.ic_pause, rs(R.string.action_pause), enabled = !d.paused) { onEvent(FireEvent.PauseClicked) }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = hintText(d.hint, d.sweeps),
                color = PicoTheme.colorScheme.labelPrimary,
                style = FireType.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (d.phase == DrillPhase.GOT) {
                WordButton("pull_pin", rs(R.string.action_pull_pin), { onEvent(FireEvent.PullPinClicked) }, primary = true, minWidth = 88.dp)
            }
        }
    }
}

@Composable
internal fun hintText(kind: HintKind, sweeps: Int): String =
    when (kind) {
        HintKind.NONE -> ""
        HintKind.COUNTDOWN_SAFETY -> rs(R.string.drill_countdown_safety)
        HintKind.LEAVE_DOOR -> rs(R.string.drill_leave_door)
        HintKind.FIRE_START -> rs(R.string.drill_fire_start)
        HintKind.GET_EXT -> rs(R.string.drill_get_ext)
        HintKind.BACK_PULL -> rs(R.string.drill_back_pull)
        HintKind.AIM -> rs(R.string.drill_aim)
        HintKind.AIM_LOW -> rs(R.string.drill_aim_low)
        HintKind.SWEEP -> rs(R.string.drill_sweep, sweeps, DrillRules.SWEEPS_NEEDED)
        HintKind.TOO_CLOSE -> rs(R.string.drill_too_close)
        HintKind.TOO_FAR -> rs(R.string.drill_too_far)
        HintKind.OUT -> rs(R.string.drill_out)
        HintKind.EMPTY -> rs(R.string.drill_empty)
        HintKind.TOO_BIG -> rs(R.string.drill_too_big)
        HintKind.DONT_FIGHT -> rs(R.string.drill_dont_fight)
    }

/** The pause menu (380 x 128 dp at 0.75), above the drill card: resume, restart, end. */
@Composable
internal fun PauseMenu(filled: Boolean, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.PAUSE_SIZE
    Glass(w.dp, h.dp, corner = 28.dp, material = com.pico.spatial.ui.platform.Material.Thick) {
        if (!filled) return@Glass
        Text(text = rs(R.string.pause_title), color = PicoTheme.colorScheme.labelPrimary, style = FireType.title, maxLines = 1)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WordButton("pause_resume", rs(R.string.action_resume), { onEvent(FireEvent.ResumeClicked) }, primary = true, modifier = Modifier.weight(1f), minWidth = 96.dp)
            WordButton("pause_restart", rs(R.string.action_restart), { onEvent(FireEvent.RestartClicked) }, modifier = Modifier.weight(1f), minWidth = 96.dp)
            WordButton("pause_end", rs(R.string.action_end_drill), { onEvent(FireEvent.EndDrillClicked) }, modifier = Modifier.weight(1f), minWidth = 96.dp)
        }
    }
}
