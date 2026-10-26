package io.embodit.fireplan.platform

import android.content.Context
import io.embodit.fireplan.data.FireRepository
import io.embodit.fireplan.data.PrefsFireRepository
import io.embodit.fireplan.platform.anchor.WorldAnchorGateway
import io.embodit.fireplan.platform.input.ControllerInput
import io.embodit.fireplan.platform.sense.PlaneTracker
import io.embodit.fireplan.platform.tracking.HeadTracker

/**
 * Process-wide wiring of the platform services. The Stage starts and stops the sensing services
 * (every start has a stop: see `FireScene.attach` / `detach`); the ViewModel only sees interfaces.
 */
object FireGraph {
    val anchors = WorldAnchorGateway()
    val planes = PlaneTracker()
    val head = HeadTracker()
    val controller = ControllerInput()

    @Volatile var appContext: Context? = null

    @Volatile private var repository: FireRepository? = null

    fun repository(context: Context): FireRepository =
        repository ?: synchronized(this) { repository ?: PrefsFireRepository(context).also { repository = it } }
}
