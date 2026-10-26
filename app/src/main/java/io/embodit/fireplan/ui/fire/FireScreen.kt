package io.embodit.fireplan.ui.fire

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.SpatialSoundEffect
import io.embodit.fireplan.platform.FireLog
import io.embodit.fireplan.ui.fire.components.FireStage
import io.embodit.fireplan.ui.fire.components.LocalFireResources
import io.embodit.fireplan.ui.fire.components.localizedResources
import io.embodit.fireplan.ui.fire.debug.FireProbe
import io.embodit.fireplan.ui.fire.scene.FireFrameSystem
import io.embodit.fireplan.ui.fire.scene.FireScene

/**
 * The one screen of the app, living in a Mixed Stage. It wires the three parts together: the
 * ViewModel holds the state, the [FireScene] turns state into entities and reports taps and the head,
 * and [FireStage] declares the panels.
 */
@Composable
fun FireScreen() {
    val context = LocalContext.current
    val viewModel: FireViewModel = viewModel(factory = FireViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current

    remember(context) {
        FireProbe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { FireScene(viewModel::onEvent) }

    DisposableEffect(scene) {
        registerSystem<FireFrameSystem>()
        FireProbe.host =
            object : FireProbe.Host {
                override fun dispatch(event: FireEvent) = viewModel.onEvent(event)

                override fun uiState(): FireUiState = viewModel.state.value

                override fun dumpJson(): String = viewModel.dumpJson()

                override fun scene(): FireProbe.SceneProbe = scene

                override fun pressControl(name: String): Boolean = FireProbe.click(name)
            }
        FireLog.i("STAGE enter")
        onDispose {
            FireProbe.host = null
            unregisterSystem<FireFrameSystem>()
            scene.detach()
            FireLog.i("STAGE disposed")
        }
    }

    // background / foreground: a running drill pauses by itself (S19)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    when (event) {
                        Lifecycle.Event.ON_STOP -> viewModel.onEvent(FireEvent.WentBackground)
                        Lifecycle.Event.ON_START -> viewModel.onEvent(FireEvent.CameForeground)
                        else -> Unit
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(scene, state) { scene.render(state) }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect: FireUiEffect ->
            when (effect) {
                FireUiEffect.ExitApp -> {
                    FireLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { FireLog.e("STAGE close failed: $it", it) }
                }
                is FireUiEffect.Sound ->
                    runCatching {
                        when (effect.kind) {
                            SoundKind.PLACE -> audio.playSystem(SpatialSoundEffect.OpClick)
                            SoundKind.DELETE -> audio.playSystem(SpatialSoundEffect.OpClose)
                            SoundKind.ARRIVE -> audio.playSystem(SpatialSoundEffect.StateSelected)
                            SoundKind.PIN -> audio.playSystem(SpatialSoundEffect.OpDragEnd)
                            SoundKind.OUT -> audio.playSystem(SpatialSoundEffect.StateSelected)
                            SoundKind.FAIL -> audio.playSystem(SpatialSoundEffect.StateFailure)
                            SoundKind.DONE -> audio.playSystem(SpatialSoundEffect.StateSelected)
                        }
                    }.onFailure { FireLog.w("SOUND failed: $it") }
            }
        }
    }

    val resources = remember(state.language, context) { localizedResources(context, state.language) }
    CompositionLocalProvider(LocalFireResources provides resources) {
        FireStage(state = state, scene = scene, onEvent = viewModel::onEvent)
    }
}
