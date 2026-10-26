package io.embodit.fireplan.ui.fire.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.fireplan.ui.fire.FireEvent
import io.embodit.fireplan.ui.fire.FireUiState
import io.embodit.fireplan.ui.fire.debug.LocalProbePanel
import io.embodit.fireplan.ui.fire.scene.FireScene
import io.embodit.fireplan.ui.fire.scene.PanelSpec

/**
 * The Stage content: one full-size SpatialView that carries the 3D scene and the panel pool. Every
 * attachment panel is declared here once, with a fixed size, whether it is showing or not; the scene
 * parents each panel entity under its holder and shows or hides the holder.
 *
 * Window model: single panel with popups. The main panel (home / tray / route card; the drill card
 * and the result panel stand in its place) is the only standing panel; strip and detector card ride
 * on markers; menu, pause menu, dialog, snackbar and coachmark are floating surfaces.
 */
@Composable
internal fun FireStage(state: FireUiState, scene: FireScene, onEvent: (FireEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue

    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                // tap and drag live in separate pointerInput blocks (two spatial recognizers must not share one)
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value ->
                        scene.onSpatialDrag("move", null, value)
                    }
                },
        attachments = {
            AttachmentPanel(PanelSpec.MAIN) {
                PoolPanel(PanelSpec.MAIN, scene, watched = true) { inView -> MainPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DRILL) {
                PoolPanel(PanelSpec.DRILL, scene, watched = true) { inView -> DrillCard(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.RESULT) {
                PoolPanel(PanelSpec.RESULT, scene, watched = true) { inView -> ResultPanel(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.STRIP) {
                PoolPanel(PanelSpec.STRIP, scene) { inView -> MarkerStrip(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DETECTOR) {
                PoolPanel(PanelSpec.DETECTOR, scene) { inView -> DetectorCard(state = state, filled = inView) }
            }
            AttachmentPanel(PanelSpec.PAUSE) {
                PoolPanel(PanelSpec.PAUSE, scene, watched = true) { inView -> PauseMenu(filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.MENU) {
                PoolPanel(PanelSpec.MENU, scene, watched = true) { inView -> MoreMenu(state = state, filled = inView, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.DIALOG) {
                PoolPanel(PanelSpec.DIALOG, scene) { inView -> DialogPanel(dialog = if (inView) state.dialog else null, state = state, onEvent = onEvent) }
            }
            AttachmentPanel(PanelSpec.SNACK) {
                PoolPanel(PanelSpec.SNACK, scene, hugs = true) { inView ->
                    SnackPanel(
                        snack = if (inView) state.snack else null,
                        onEvent = onEvent,
                        applied = scene.panelStretch[PanelSpec.SNACK] ?: 1f,
                        onWidth = { scene.reportSnack(it) },
                    )
                }
            }
            AttachmentPanel(PanelSpec.COACH) {
                PoolPanel(PanelSpec.COACH, scene) { inView -> CoachPanel(shown = inView && state.coach, onEvent = onEvent) }
            }
        },
        update = { _, attachments ->
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * Root of a pooled panel. [content] gets `inView`: the panel has been moved into view. A pooled
 * panel must draw its content only then (what it draws while parked out of sight stays broken on the
 * emulator). [watched]: presses on it hold the lazy follow still. [hugs]: the content is narrower
 * than the panel (snackbar capsule) and reports its width to the scene.
 */
@Composable
private fun PoolPanel(id: String, scene: FireScene, watched: Boolean = false, hugs: Boolean = false, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    CompositionLocalProvider(LocalProbePanel provides id) {
        if (watched && !hugs) {
            Box(
                modifier =
                    Modifier.pointerInput(id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) scene.onPanelPointer(true)
                                if (event.type == PointerEventType.Release) scene.onPanelPointer(false)
                            }
                        }
                    }
            ) {
                content(inView)
            }
        } else {
            content(inView)
        }
    }
}
