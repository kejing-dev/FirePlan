package io.embodit.fireplan.ui.fire.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.fireplan.R
import io.embodit.fireplan.domain.model.DrillRules
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.Scenario
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.domain.usecase.Finding
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.ResultUi
import io.embodit.fireplan.ui.fire.scene.PanelSpec
import io.embodit.fireplan.ui.theme.FireColors
import kotlin.math.max
import kotlin.math.min

/**
 * The result panel (520 x 440 dp at 0.75, S18): total time against the 2:00 line, the scenario, the
 * segment bar (get extinguisher / fight / get out), the top-down map (markers, route dots, this
 * drill's trail as a dashed line, the floor outline), up to four findings, the fixed pan-fire line,
 * "last -> now" and the two buttons.
 */
@Composable
internal fun ResultPanel(state: FireUiState, filled: Boolean, onEvent: (FireEvent) -> Unit) {
    val (w, h) = PanelSpec.RESULT_SIZE
    Glass(w.dp, h.dp) {
        if (!filled) return@Glass
        val r = state.result ?: return@Glass
        val under = r.totalS <= DrillRules.TWO_MINUTES_S
        val ink = PicoTheme.colorScheme.labelPrimary
        val dim = PicoTheme.colorScheme.labelSecondary
        Row(modifier = Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = fmtTime(r.totalS), color = ink, style = FireType.timer, maxLines = 1, softWrap = false)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (under) rs(R.string.result_under) else rs(R.string.result_over),
                    color = if (under) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.alert,
                    style = FireType.title,
                    maxLines = 1,
                )
                Text(text = rs(R.string.result_title) + " · " + (if (r.scenario == Scenario.BIG) rs(R.string.scenario_big) else rs(R.string.scenario_small)), color = dim, style = FireType.figure, maxLines = 1)
            }
        }
        SegmentBar(r)
        Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TopDownMap(state, r, modifier = Modifier.size(240.dp, 200.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (f in r.findings) {
                    val ok = f == Finding.OK
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            painter = painterResource(if (ok) R.drawable.ic_check else R.drawable.ic_warning),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (ok) PicoTheme.colorScheme.passable else PicoTheme.colorScheme.alert,
                        )
                        Text(text = findingText(f, r.detectorsToMove), color = ink, style = PicoTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(text = rs(R.string.result_oil_tip), color = dim, style = PicoTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = r.prevS?.let { rs(R.string.result_compare, fmtTime(it), fmtTime(r.totalS)) } ?: "",
                color = dim,
                style = FireType.figure,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            WordButton("result_again", rs(R.string.action_again), { onEvent(FireEvent.AgainClicked) })
            WordButton("result_done", rs(R.string.action_finish), { onEvent(FireEvent.ResultDoneClicked) }, primary = true)
        }
    }
}

private val SegmentColors = listOf(FireColors.Interaction, FireColors.Alert, FireColors.Passable)

@Composable
private fun SegmentBar(r: ResultUi) {
    val segs = r.segments.filter { it.seconds > 0f }
    val total = segs.sumOf { it.seconds.toDouble() }.toFloat().coerceAtLeast(1f)
    Row(modifier = Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (s in segs) {
            Box(modifier = Modifier.weight((s.seconds / total).coerceAtLeast(0.02f)).height(18.dp).drawBehind { drawRect(SegmentColors[s.kind.coerceIn(0, 2)]) }, contentAlignment = Alignment.Center) {
                Text(text = "${s.seconds.toInt()}s", color = FireColors.Ink, style = PicoTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (s in segs) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).drawBehind { drawRect(SegmentColors[s.kind.coerceIn(0, 2)]) })
                Text(
                    text =
                        when (s.kind) {
                            0 -> rs(R.string.seg_get)
                            1 -> rs(R.string.seg_fight)
                            else -> rs(R.string.seg_out)
                        },
                    color = PicoTheme.colorScheme.labelSecondary,
                    style = PicoTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The small top-down map: floor outline, route dots, this drill's trail (dashed), markers in their colours. */
@Composable
private fun TopDownMap(state: FireUiState, r: ResultUi, modifier: Modifier) {
    val markers = state.markers
    val routes = state.routes
    val outline = state.floorOutline
    val frame = PicoTheme.colorScheme.fillTertiary
    Box(
        modifier =
            modifier.clip(RoundedCornerShape(12.dp)).drawBehind {
                drawRect(frame)
                // fit every point into the box with a 1 m margin, keeping the aspect
                var minX = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var minZ = Float.MAX_VALUE
                var maxZ = -Float.MAX_VALUE
                fun take(p: Vec3) {
                    minX = min(minX, p.x); maxX = max(maxX, p.x); minZ = min(minZ, p.z); maxZ = max(maxZ, p.z)
                }
                markers.forEach { take(it.position) }
                routes.values.forEach { pts -> pts.forEach(::take) }
                r.trail.forEach(::take)
                outline.forEach(::take)
                if (minX > maxX) return@drawBehind
                minX -= 1f; maxX += 1f; minZ -= 1f; maxZ += 1f
                val sx = size.width / (maxX - minX)
                val sz = size.height / (maxZ - minZ)
                val s = min(sx, sz)
                val ox = (size.width - (maxX - minX) * s) / 2f
                val oz = (size.height - (maxZ - minZ) * s) / 2f
                fun at(p: Vec3) = Offset(ox + (p.x - minX) * s, oz + (p.z - minZ) * s)
                if (outline.size >= 3) {
                    val path = Path()
                    outline.forEachIndexed { i, p -> if (i == 0) path.moveTo(at(p).x, at(p).y) else path.lineTo(at(p).x, at(p).y) }
                    path.close()
                    drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(width = 3f))
                }
                for (pts in routes.values) for (p in pts) drawCircle(FireColors.Route, radius = 3.5f, center = at(p))
                if (r.trail.size > 1) {
                    val path = Path()
                    r.trail.forEachIndexed { i, p -> if (i == 0) path.moveTo(at(p).x, at(p).y) else path.lineTo(at(p).x, at(p).y) }
                    drawPath(path, Color.White, style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f))))
                }
                for (m in markers) {
                    val c =
                        when (m.kind) {
                            MarkerKind.FIRE -> FireColors.MarkerFire
                            MarkerKind.EXTINGUISHER -> FireColors.MarkerExtinguisher
                            MarkerKind.EXIT -> FireColors.MarkerExit
                            MarkerKind.DETECTOR -> FireColors.MarkerDetector
                        }
                    drawCircle(c, radius = 8f, center = at(m.position))
                }
            }
    )
}

@Composable
internal fun findingText(f: Finding, detectorsToMove: Int): String =
    when (f) {
        Finding.F1_TOO_BIG_TO_FIGHT -> rs(R.string.finding_f1)
        Finding.F2_OVER_TWO_MINUTES -> rs(R.string.finding_f2)
        Finding.F3_SECOND_EXTINGUISHER -> rs(R.string.finding_f3)
        Finding.F4_SECOND_EXIT -> rs(R.string.finding_f4)
        Finding.F5_NO_DETECTOR -> rs(R.string.finding_f5)
        Finding.F6_DETECTORS_TO_MOVE -> rs(R.string.finding_f6, detectorsToMove)
        Finding.F7_DISTANCE -> rs(R.string.finding_f7)
        Finding.OK -> rs(R.string.finding_ok)
    }

@Suppress("unused") private val keepWidth = 0.dp.let { Modifier.width(it) }
