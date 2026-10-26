package io.embodit.fireplan.ui.fire.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.fireplan.R
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.scene.PanelSpec

/** The marker strip (232 x 64 dp at 0.75) above a selected marker: name (+ distance to the fire), delete, close. */
@Composable
internal fun MarkerStrip(state: FireUiState, filled: Boolean, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.STRIP_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, padding = 8.dp) {
        if (!filled) return@Glass
        val m = state.selectedMarker ?: return@Glass
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(modifier = Modifier.weight(1f).height(48.dp), verticalArrangement = Arrangement.Center) {
                Text(text = kindName(m.kind), color = PicoTheme.colorScheme.labelPrimary, style = FireType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val d = m.fromFireM
                if (d != null && (m.kind == MarkerKind.EXTINGUISHER || m.kind == MarkerKind.EXIT)) {
                    Text(text = rs(R.string.marker_from_fire, fmt1(d)), color = PicoTheme.colorScheme.labelSecondary, style = PicoTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }
            Key("strip_delete", R.drawable.ic_trash, rs(R.string.action_delete)) { onEvent(FireEvent.DeleteMarkerClicked) }
            Key("strip_close", R.drawable.ic_close, rs(R.string.action_close)) { onEvent(FireEvent.CloseStripClicked) }
        }
    }
}

/**
 * The detector check card (296 x 176 dp at 0.75) below a selected detector: three rows (result icon,
 * a sentence, the rule) and the basis line (GB 50116 / NFPA 72).
 */
@Composable
internal fun DetectorCard(state: FireUiState, filled: Boolean) {
    val (w, h) = PanelSpec.DETECTOR_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, padding = 12.dp) {
        if (!filled) return@Glass
        val c = state.detector ?: return@Glass
        if (c.onCeiling) {
            CheckRow(ok = true, text = rs(R.string.det_ceiling), rule = "")
            CheckRow(ok = c.wallOk, text = c.wallDistM?.let { rs(R.string.det_wall, fmt2(it)) } ?: rs(R.string.det_wall_unknown), rule = rs(R.string.det_rule_wall))
        } else {
            CheckRow(ok = false, text = rs(R.string.det_prefer_ceiling), rule = "")
            CheckRow(ok = c.topOk, text = rs(R.string.det_from_top, fmt2(c.belowCeilingM ?: 0f)), rule = rs(R.string.det_rule_top))
        }
        CheckRow(ok = c.stoveOk, text = c.stoveDistM?.let { rs(R.string.det_stove, fmt1(it)) } ?: rs(R.string.det_stove_unknown), rule = rs(R.string.det_rule_stove))
        Spacer(modifier = Modifier.weight(1f))
        Text(text = rs(R.string.det_basis), color = PicoTheme.colorScheme.labelTertiary, style = PicoTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun CheckRow(ok: Boolean, text: String, rule: String) {
    Row(modifier = Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            painter = painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (ok) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.alert,
        )
        Text(text = text, color = PicoTheme.colorScheme.labelPrimary, style = FireType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (rule.isNotEmpty()) Text(text = rule, color = PicoTheme.colorScheme.labelSecondary, style = FireType.figure, maxLines = 1, softWrap = false)
    }
}
