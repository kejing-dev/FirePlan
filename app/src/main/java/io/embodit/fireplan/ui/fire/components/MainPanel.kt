package io.embodit.fireplan.ui.fire.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.fireplan.R
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.RoomBox
import io.embodit.fireplan.domain.model.RoomSource
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.Mode
import io.embodit.fireplan.ui.fire.RoutePhase
import io.embodit.fireplan.ui.fire.debug.probe
import io.embodit.fireplan.ui.fire.scene.PanelSpec
import io.embodit.fireplan.ui.theme.FireColors

/**
 * The main panel (400 x 300 dp at 0.75): home (S00 - S03), the setup tray (S04 / S05), the exit
 * pick (S06) and the route card (S07 / S08), and (v0.1.1) the room card. Its content is swapped by
 * mode; the drill card and the result panel are separate pooled panels in the same place.
 */
@Composable
internal fun MainPanel(state: FireUiState, filled: Boolean, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.MAIN_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        if (state.roomEdit && state.room != null) {
            RoomCard(state, state.room, onEvent)
            return@Glass
        }
        when (state.mode) {
            Mode.PLACING -> SetupTray(state, onEvent)
            Mode.ROUTE_PICK -> ExitPick(state, onEvent)
            Mode.ROUTE_RECORDING, Mode.ROUTE_DONE -> RouteCard(state, onEvent)
            else -> Home(state, onEvent)
        }
    }
}

@Composable
private fun Home(state: FireUiState, onEvent: (FireEvent) -> Unit) {
    val starting = state.starting
    TitleRow(
        title = rs(R.string.home_title),
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Key("room", R.drawable.ic_room, rs(R.string.action_room), enabled = !starting && state.room != null) { onEvent(FireEvent.RoomClicked) }
                Key("more", R.drawable.ic_more, rs(R.string.action_more), enabled = !starting) { onEvent(FireEvent.MoreClicked) }
            }
        },
    )
    if (starting) {
        Row(modifier = Modifier.fillMaxWidth().height(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PicoTheme.colorScheme.labelPrimary, progressSize = CircularProgressDefaults.circleProgressSize(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = rs(R.string.home_starting), color = PicoTheme.colorScheme.labelSecondary, style = FireType.figure, maxLines = 1)
        }
    }
    val n = state.placedCount
    BigButton(
        name = "home_setup",
        iconRes = R.drawable.ic_pin,
        text = rs(R.string.home_setup),
        sub = if (n > 0) rs(R.string.home_placed, n) else rs(R.string.home_not_set_up),
        enabled = !starting,
        onClick = { onEvent(FireEvent.SetupClicked) },
    )
    BigButton(
        name = "home_drill",
        iconRes = R.drawable.ic_flame,
        text = rs(R.string.home_drill),
        sub = "",
        enabled = !starting && state.canDrill,
        onClick = { onEvent(FireEvent.DrillClicked) },
        iconTint = FireColors.MarkerFire,
    )
    if (!starting && !state.canDrill) {
        Text(text = rs(R.string.home_need_fire_exit), color = PicoTheme.colorScheme.labelSecondary, style = FireType.figure, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
    BigButton(
        name = "home_result",
        iconRes = R.drawable.ic_clock,
        text = rs(R.string.home_result),
        sub = state.lastTotalS?.let { fmtTime(it) } ?: "",
        enabled = !starting && state.hasRecord,
        onClick = { onEvent(FireEvent.ResultClicked) },
    )
}

/** The setup tray: four tiles (what the next pinch places) and the route button. */
@Composable
private fun SetupTray(state: FireUiState, onEvent: (FireEvent) -> Unit) {
    TitleRow(
        title = rs(R.string.tray_title),
        leading = { Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(FireEvent.BackClicked) } },
        trailing = { Key("tray_room", R.drawable.ic_room, rs(R.string.action_room), enabled = state.room != null) { onEvent(FireEvent.RoomClicked) } },
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(state, MarkerKind.FIRE, onEvent, Modifier.weight(1f))
        Tile(state, MarkerKind.EXIT, onEvent, Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(state, MarkerKind.EXTINGUISHER, onEvent, Modifier.weight(1f))
        Tile(state, MarkerKind.DETECTOR, onEvent, Modifier.weight(1f))
    }
    BigButton(
        name = "tray_route",
        iconRes = R.drawable.ic_route,
        text = rs(R.string.tray_route),
        sub = "${state.routeCount}/${maxOf(1, state.exitCount)}",
        enabled = state.canDrill,
        onClick = { onEvent(FireEvent.RouteClicked) },
        height = 56.dp,
    )
}

/** One marker tile (180 x 72): icon in the type colour, name, count; the selected one on an interaction fill. */
@Composable
private fun Tile(state: FireUiState, kind: MarkerKind, onEvent: (FireEvent) -> Unit, modifier: Modifier) {
    val count = state.count(kind)
    val full = state.full(kind)
    val selected = state.placeKind == kind
    val color = kindColor(kind)
    val content = if (full) PicoTheme.colorScheme.labelQuaternary else PicoTheme.colorScheme.labelPrimary
    val onClick = { onEvent(FireEvent.TileClicked(kind)) }
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp).probe("tile_${kind.tag}", onClick),
        size = ButtonDefaults.Regular.copy(height = 72.dp, minWidth = 160.dp),
        shape = RoundedCornerShape(20.dp),
        enabled = !full,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (selected) PicoTheme.colorScheme.interaction.copy(alpha = 0.32f) else PicoTheme.colorScheme.fillTertiary,
                contentColor = content,
            ),
    ) {
        Icon(painter = painterResource(kindIcon(kind)), contentDescription = null, modifier = Modifier.size(28.dp), tint = if (full) content else color)
        Spacer(modifier = Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = kindName(kind), color = content, style = FireType.figure, maxLines = 1)
            Text(text = if (full) rs(R.string.marker_full) else "$count/${kind.limit}", color = if (full) content else PicoTheme.colorScheme.labelSecondary, style = FireType.figure, maxLines = 1)
        }
    }
}

@Composable
private fun ExitPick(state: FireUiState, onEvent: (FireEvent) -> Unit) {
    TitleRow(title = rs(R.string.route_pick), leading = { Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(FireEvent.BackClicked) } })
    val exits = state.markers.filter { it.kind == MarkerKind.EXIT }
    for ((i, e) in exits.withIndex()) {
        BigButton(
            name = "pick_exit${i + 1}",
            iconRes = R.drawable.mk_exit,
            text = (if (i == 0) rs(R.string.exit_1) else rs(R.string.exit_2)) + "  " + rs(R.string.marker_exit),
            sub = e.fromFireM?.let { rs(R.string.marker_from_fire, fmt1(it)) } ?: "",
            enabled = true,
            onClick = { onEvent(FireEvent.ExitPicked(e.id)) },
            iconTint = FireColors.MarkerExit,
        )
    }
}

@Composable
private fun ColumnScope.RouteCard(state: FireUiState, onEvent: (FireEvent) -> Unit) {
    TitleRow(title = rs(R.string.tray_route), leading = { Key("back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(FireEvent.BackClicked) } })
    val rec = state.routeRec ?: return
    val big = PicoTheme.typography.headlineSmall
    when (rec.phase) {
        RoutePhase.TO_FIRE -> {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = rs(R.string.route_walk_to_fire, fmt1(rec.toFireM)), color = PicoTheme.colorScheme.labelPrimary, style = big, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.weight(1f))
            // v0.1.1 (taste profile B17): the route can be drawn without walking it
            Text(text = rs(R.string.route_or_draw), color = PicoTheme.colorScheme.labelSecondary, style = FireType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                WordButton("route_auto", rs(R.string.route_auto), { onEvent(FireEvent.RouteAutoClicked) }, primary = true, minWidth = 160.dp, iconRes = R.drawable.ic_route)
            }
        }
        RoutePhase.TO_EXIT -> {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = rs(R.string.route_go), color = PicoTheme.colorScheme.labelPrimary, style = big, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(text = rs(R.string.route_walked, fmt1(rec.walkedM)), color = PicoTheme.colorScheme.labelSecondary, style = FireType.figure, maxLines = 1, modifier = Modifier.weight(1f))
                Key("route_stop", R.drawable.ic_stop, rs(R.string.action_stop)) { onEvent(FireEvent.RouteStopClicked) }
            }
        }
        RoutePhase.DONE, RoutePhase.SHORT -> {
            Spacer(modifier = Modifier.height(16.dp))
            val ok = rec.phase == RoutePhase.DONE
            Text(
                text = if (ok) rs(R.string.route_done, fmt1(rec.walkedM)) else rs(R.string.route_short),
                color = PicoTheme.colorScheme.labelPrimary,
                style = big,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                WordButton("route_redo", rs(R.string.action_redo), { onEvent(FireEvent.RouteRedoClicked) })
                if (ok) WordButton("route_finish", rs(R.string.action_finish), { onEvent(FireEvent.RouteDoneClicked) }, primary = true)
            }
        }
    }
}

/**
 * The room card (v0.1.1, taste profile D7): size of the room box (width / length in 0.5 m steps, height in
 * 0.1 m steps) on the left, its placement relative to the viewer (turn 5 degrees, slide and push 0.25 m)
 * on the right; "around me" puts it back around the viewer. Every press acts at once in the room.
 */
@Composable
private fun RoomCard(state: FireUiState, room: RoomBox, onEvent: (FireEvent) -> Unit) {
    TitleRow(
        title = rs(R.string.room_title, room.label),
        leading = { Key("room_back", R.drawable.ic_back, rs(R.string.action_back)) { onEvent(FireEvent.RoomDoneClicked) } },
        trailing = { WordButton("room_recenter", rs(R.string.action_recenter), { onEvent(FireEvent.RoomRecentered) }, minWidth = 104.dp) },
    )
    val status =
        when {
            state.roomSource != RoomSource.SCAN -> rs(R.string.room_virtual)
            state.ceilingScanned -> rs(R.string.room_floor_ceiling)
            else -> rs(R.string.room_floor)
        }
    Text(text = status, color = PicoTheme.colorScheme.labelSecondary, style = FireType.figure, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val w = RoomBox.SIZE_STEP_M
    val h = RoomBox.HEIGHT_STEP_M
    val m = RoomBox.MOVE_STEP_M
    val t = RoomBox.TURN_STEP_DEG
    StepRow(rs(R.string.room_w) + " " + fmt1(room.width), "room_w", R.drawable.ic_minus, R.drawable.ic_plus, { onEvent(FireEvent.RoomResized(dw = -w)) }, { onEvent(FireEvent.RoomResized(dw = w)) },
        rs(R.string.room_turn), "room_turn", R.drawable.ic_turn_left, R.drawable.ic_turn_right, { onEvent(FireEvent.RoomMoved(turnDeg = -t)) }, { onEvent(FireEvent.RoomMoved(turnDeg = t)) })
    StepRow(rs(R.string.room_l) + " " + fmt1(room.length), "room_l", R.drawable.ic_minus, R.drawable.ic_plus, { onEvent(FireEvent.RoomResized(dl = -w)) }, { onEvent(FireEvent.RoomResized(dl = w)) },
        rs(R.string.room_side), "room_side", R.drawable.ic_arrow_left, R.drawable.ic_arrow_right, { onEvent(FireEvent.RoomMoved(right = -m)) }, { onEvent(FireEvent.RoomMoved(right = m)) })
    StepRow(rs(R.string.room_h) + " " + fmt1(room.height), "room_h", R.drawable.ic_minus, R.drawable.ic_plus, { onEvent(FireEvent.RoomResized(dh = -h)) }, { onEvent(FireEvent.RoomResized(dh = h)) },
        rs(R.string.room_depth), "room_depth", R.drawable.ic_arrow_down, R.drawable.ic_arrow_up, { onEvent(FireEvent.RoomMoved(ahead = -m)) }, { onEvent(FireEvent.RoomMoved(ahead = m)) })
}

/** One row of the room card: a size pair (label, minus, plus) and a placement pair (label, two keys). */
@Composable
private fun StepRow(
    sizeLabel: String,
    sizeName: String,
    lessIcon: Int,
    moreIcon: Int,
    onLess: () -> Unit,
    onMore: () -> Unit,
    moveLabel: String,
    moveName: String,
    aIcon: Int,
    bIcon: Int,
    onA: () -> Unit,
    onB: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(KeyHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = sizeLabel, color = PicoTheme.colorScheme.labelPrimary, style = FireType.figure, maxLines = 1, softWrap = false, modifier = Modifier.width(84.dp))
        Key("${sizeName}_minus", lessIcon, sizeLabel, width = 48.dp, onClick = onLess)
        Key("${sizeName}_plus", moreIcon, sizeLabel, width = 48.dp, onClick = onMore)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = moveLabel, color = PicoTheme.colorScheme.labelPrimary, style = FireType.figure, maxLines = 1, softWrap = false, modifier = Modifier.width(44.dp))
        Key("${moveName}_a", aIcon, moveLabel, width = 48.dp, onClick = onA)
        Key("${moveName}_b", bIcon, moveLabel, width = 48.dp, onClick = onB)
    }
}

@Composable
internal fun kindName(kind: MarkerKind): String =
    when (kind) {
        MarkerKind.FIRE -> rs(R.string.marker_fire)
        MarkerKind.EXTINGUISHER -> rs(R.string.marker_ext)
        MarkerKind.EXIT -> rs(R.string.marker_exit)
        MarkerKind.DETECTOR -> rs(R.string.marker_det)
    }

internal fun kindIcon(kind: MarkerKind): Int =
    when (kind) {
        MarkerKind.FIRE -> R.drawable.mk_fire
        MarkerKind.EXTINGUISHER -> R.drawable.mk_extinguisher
        MarkerKind.EXIT -> R.drawable.mk_exit
        MarkerKind.DETECTOR -> R.drawable.mk_detector
    }

internal fun kindColor(kind: MarkerKind): Color =
    when (kind) {
        MarkerKind.FIRE -> FireColors.MarkerFire
        MarkerKind.EXTINGUISHER -> FireColors.MarkerExtinguisher
        MarkerKind.EXIT -> FireColors.MarkerExit
        MarkerKind.DETECTOR -> FireColors.MarkerDetector
    }

/** Keeps the unused import warnings quiet on builds that strip them. */
@Suppress("unused") private val unusedBox: @Composable () -> Unit = { Box(Modifier.size(0.dp)) }
