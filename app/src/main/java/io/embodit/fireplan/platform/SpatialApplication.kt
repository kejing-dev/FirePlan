package io.embodit.fireplan.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.fireplan.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FireGraph.appContext = applicationContext
        launch(::mainApp)
    }
}
