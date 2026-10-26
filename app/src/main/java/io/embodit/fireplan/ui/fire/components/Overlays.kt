package io.embodit.fireplan.ui.fire.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.fireplan.R
import io.embodit.fireplan.ui.fire.DialogKind
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.SnackKind
import io.embodit.fireplan.ui.fire.SnackUi
import io.embodit.fireplan.ui.fire.debug.probe
import io.embodit.fireplan.ui.fire.scene.PanelSpec
import kotlin.math.abs

private const val FADE_IN_MS = 150

/**
 * The dialogs (self-built attachment panel, 480 x 280 dp at 1.125, 0.9 m ahead): clear confirmation
 * (S21), "your last setup isn't here" (S10) and "room sensing isn't available" (S22). In a Stage the
 * SpatialUI `AlertDialog` composes nothing (Spatial Ruler finding), so the card is built to its metrics.
 */
@Composable
internal fun DialogPanel(dialog: DialogKind?, state: FireUiState, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.DIALOG_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (dialog == null) return@Box
        val fade = remember(dialog) { Animatable(0f) }
        LaunchedEffect(dialog) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(
            modifier =
                Modifier
                    .width(400.dp)
                    .heightIn(max = h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(32.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .padding(32.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(
                    painter =
                        painterResource(
                            when (dialog) {
                                DialogKind.CLEAR -> R.drawable.ic_trash
                                DialogKind.NOT_HERE -> R.drawable.ic_pin
                                DialogKind.UNAVAILABLE -> R.drawable.ic_unavailable
                            }
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = if (dialog == DialogKind.CLEAR) PicoTheme.colorScheme.alert else ink,
                )
                Text(
                    text =
                        when (dialog) {
                            DialogKind.CLEAR -> rs(R.string.dialog_clear)
                            DialogKind.NOT_HERE -> rs(R.string.dialog_not_here)
                            DialogKind.UNAVAILABLE -> rs(R.string.dialog_unavailable)
                        },
                    color = ink,
                    style = PicoTheme.typography.headlineSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when (dialog) {
                    DialogKind.CLEAR -> {
                        WordButton("dialog_cancel", rs(R.string.action_cancel), { onEvent(FireEvent.DialogDismissed) }, minWidth = 120.dp, iconRes = R.drawable.ic_close)
                        WordButton("dialog_confirm", rs(R.string.action_clear), { onEvent(FireEvent.ClearConfirmed) }, danger = true, minWidth = 120.dp, iconRes = R.drawable.ic_trash)
                    }
                    DialogKind.NOT_HERE -> {
                        WordButton("dialog_retry", rs(R.string.action_look_again), { onEvent(FireEvent.NotHereRetry) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_retry)
                        WordButton("dialog_redo", rs(R.string.action_set_up_again), { onEvent(FireEvent.NotHereRedo) }, minWidth = 120.dp)
                    }
                    DialogKind.UNAVAILABLE -> {
                        WordButton("dialog_exit", rs(R.string.action_exit), { onEvent(FireEvent.ExitClicked) }, primary = true, minWidth = 120.dp, iconRes = R.drawable.ic_exit)
                    }
                }
            }
        }
    }
}

/** Snackbar copy: the message (Chinese at most 10 characters), an optional action word, and the leading glyph. */
@Composable
private fun snackText(s: SnackUi): String =
    when (s.kind) {
        SnackKind.SCANNING -> rs(R.string.snack_scanning)
        SnackKind.RESTORING -> rs(R.string.snack_restoring)
        SnackKind.RESTORED -> rs(R.string.snack_restored, s.a)
        SnackKind.PARTIAL -> rs(R.string.snack_partial, s.a, s.b)
        SnackKind.BAD_SURFACE -> rs(R.string.snack_bad_surface)
        SnackKind.UNSAVED -> rs(R.string.snack_unsaved)
        SnackKind.LOST -> rs(R.string.snack_lost)
        SnackKind.MAX -> rs(R.string.snack_max, s.a)
    }

/**
 * The snackbar (head-locked attachment panel 14 degrees above the view centre, 560 x 72 dp at 1.125):
 * 28 dp corners, Material.Thick, a leading icon or spinner, one line of text, an optional action.
 * The capsule takes the width of its message inside the fixed-size panel ([applied]: the scene
 * squeezes the panel to the capsule's width and the capsule is drawn stretched back).
 */
@Composable
internal fun SnackPanel(snack: SnackUi?, onEvent: (FireEvent) -> Unit, applied: Float = 1f, onWidth: (Float) -> Unit = {}) {
    val (w, h) = PanelSpec.SNACK_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (snack == null) return@Box
        val density = LocalDensity.current
        val panelPx = with(density) { w.dp.toPx() }
        val ink = PicoTheme.colorScheme.labelPrimary
        val fade = remember(snack.id) { Animatable(0f) }
        LaunchedEffect(snack.id) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .widthIn(min = 240.dp, max = w.dp)
                    .defaultMinSize(minHeight = 56.dp)
                    .onSizeChanged { onWidth(it.width / density.density) }
                    .graphicsLayer {
                        val own = size.width / panelPx
                        scaleX = 1f / applied
                        alpha = if (abs(applied - own) <= own * 0.12f) fade.value else 0f
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("snackbar")
                    .padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val spinner = snack.kind == SnackKind.SCANNING || snack.kind == SnackKind.RESTORING
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = ink, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            } else {
                Icon(
                    painter =
                        painterResource(
                            when (snack.kind) {
                                SnackKind.RESTORED -> R.drawable.ic_check
                                SnackKind.PARTIAL -> R.drawable.ic_pin
                                SnackKind.BAD_SURFACE -> R.drawable.ic_unavailable
                                SnackKind.UNSAVED, SnackKind.MAX -> R.drawable.ic_warning
                                SnackKind.LOST -> R.drawable.ic_unavailable
                                else -> R.drawable.ic_check
                            }
                        ),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = ink,
                )
            }
            Text(
                text = snackText(snack),
                color = ink,
                style = PicoTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum"),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 4.dp),
            )
            if (snack.kind == SnackKind.UNSAVED) {
                WordButton("snackbar_action", rs(R.string.action_retry), { onEvent(FireEvent.SnackActionClicked(snack.id)) }, primary = true, minWidth = 96.dp, iconRes = R.drawable.ic_retry)
            }
        }
    }
}

/** First-run coachmark (360 x 80 dp at 0.75, S23) beside the tray: pictogram, seven words, got it. */
@Composable
internal fun CoachPanel(shown: Boolean, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.COACH_SIZE
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (!shown) return@Box
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(FADE_IN_MS)) }
        Row(
            modifier =
                Modifier
                    .size(w.dp, h.dp)
                    .graphicsLayer { alpha = fade.value }
                    .clip(RoundedCornerShape(24.dp))
                    .backgroundMaterial(enable = true, style = Material.Thick)
                    .probe("coach")
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.ic_route), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
                Icon(painter = painterResource(R.drawable.ic_pin), contentDescription = null, modifier = Modifier.size(24.dp), tint = PicoTheme.colorScheme.labelPrimary)
            }
            Text(
                text = rs(R.string.coach_hint),
                color = PicoTheme.colorScheme.labelPrimary,
                style = PicoTheme.typography.bodyLargeMultiline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            WordButton("coach_got_it", rs(R.string.action_got_it), { onEvent(FireEvent.CoachDismissed) }, primary = true, minWidth = 88.dp)
        }
    }
}
