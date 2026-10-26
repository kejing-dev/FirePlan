package io.embodit.fireplan.ui.fire.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.platform.Material
import io.embodit.fireplan.R
import io.embodit.fireplan.domain.model.LanguagePref
import io.embodit.fireplan.domain.model.ScenarioPref
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.debug.probe
import io.embodit.fireplan.ui.fire.scene.PanelSpec

/**
 * The More menu (400 x 256 dp at 0.75, S20): language, hints and scenario segments, "Clear all" and
 * Exit. Segments act in place and leave the menu open; Clear all opens the dialog; Exit closes the Stage.
 */
@Composable
internal fun MoreMenu(state: FireUiState, filled: Boolean, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.MENU_SIZE
    Glass(w.dp, h.dp, corner = 24.dp, material = Material.Thick, padding = 12.dp) {
        if (!filled) return@Glass
        MenuRow(rs(R.string.menu_language)) {
            Segments(
                items = listOf("lang_system" to null, "lang_zh" to rs(R.string.lang_zh), "lang_en" to rs(R.string.lang_en_short)),
                selected = state.language.ordinal,
                onSelect = { onEvent(FireEvent.LanguageSelected(LanguagePref.entries[it])) },
                leadingIcon = R.drawable.ic_globe,
            )
        }
        MenuRow(rs(R.string.menu_hints)) {
            Segments(items = listOf("hints_on" to rs(R.string.on), "hints_off" to rs(R.string.off)), selected = if (state.hints) 0 else 1, onSelect = { onEvent(FireEvent.HintsSelected(it == 0)) })
        }
        MenuRow(rs(R.string.menu_scenario)) {
            Segments(
                items = listOf("scen_random" to rs(R.string.scen_random), "scen_small" to rs(R.string.scen_small), "scen_big" to rs(R.string.scen_big)),
                selected = state.scenarioPref.ordinal,
                onSelect = { onEvent(FireEvent.ScenarioSelected(ScenarioPref.entries[it])) },
            )
        }
        Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WordButton("menu_clear", rs(R.string.menu_clear), { onEvent(FireEvent.ClearAllClicked) }, enabled = state.placedCount > 0 || state.hasRecord, minWidth = 160.dp, iconRes = R.drawable.ic_trash)
            Spacer(modifier = Modifier.weight(1f))
            Key("menu_exit", R.drawable.ic_exit, rs(R.string.menu_exit)) { onEvent(FireEvent.ExitClicked) }
        }
    }
}

@Composable
private fun MenuRow(label: String, content: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, color = PicoTheme.colorScheme.labelPrimary, style = FireType.figure, maxLines = 1, modifier = Modifier.width(88.dp))
        content()
    }
}

/** A SegmentControl whose items are 80 x 48; an item with a null text shows [leadingIcon] instead. */
@Composable
private fun Segments(items: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, leadingIcon: Int? = null) {
    SegmentControl(modifier = Modifier.width((items.size * 80).dp).fillMaxHeight(), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
        items.forEachIndexed { i, (name, text) ->
            val onClick = { onSelect(i) }
            SegmentItem(
                selected = selected == i,
                onClick = onClick,
                modifier = Modifier.width(80.dp).height(KeyHeight).probe(name, onClick),
                textStyle = PicoTheme.typography.labelLarge,
                colors =
                    SegmentControlDefaults.colors(
                        itemBackgroundColor = Color.Transparent,
                        itemContentColor = PicoTheme.colorScheme.labelPrimary,
                        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
                        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
                    ),
                title = {
                    if (text == null && leadingIcon != null) {
                        Icon(painter = painterResource(leadingIcon), contentDescription = rs(R.string.lang_system), modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary)
                    } else {
                        // design-style: inherited-content-color SegmentItem
                        Text(text = text ?: "", maxLines = 1, softWrap = false, overflow = TextOverflow.Visible)
                    }
                },
            )
        }
    }
}
