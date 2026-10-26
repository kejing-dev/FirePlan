package io.embodit.fireplan.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design package `palette`). This is the only file with colour literals.
 * Five `ColorScheme` roles are fixed here and injected into `PicoTheme` by [FirePlanTheme]; the
 * other eleven inherit SpatialUI Vibrant. The brand tokens are used by the 3D materials and the
 * solid-coloured faces (icon plates), where adaptive colours must not land.
 */
object FireColors {
    // ---- ColorScheme roles with a fixed design value
    val LabelPrimaryLight = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val Error = Color(0xFFFF5F52) // design-style: fixed-figma-color error
    val Alert = Color(0xFFFF8A1F) // design-style: fixed-figma-color alert: over two minutes, distance wrong, detector warning
    val Passable = Color(0xFF66E08A) // design-style: fixed-figma-color passable: route dots, "out in under 2 min", check rows
    val Interaction = Color(0xFFFFD23F) // design-style: fixed-figma-color interaction: base ring, selected tile, pin ring

    // ---- brand tokens (design `palette.brand`)
    val MarkerFire = Color(0xFFFF7A1A) // design-style: fixed-figma-color markerFire: fire spot, flame outer layer
    val MarkerExtinguisher = Color(0xFFE23B2E) // design-style: fixed-figma-color markerExtinguisher: extinguisher marker and prop body
    val MarkerExit = Color(0xFF2FBF66) // design-style: fixed-figma-color markerExit: exit marker (safety-sign green)
    val MarkerDetector = Color(0xFF8FC7FF) // design-style: fixed-figma-color markerDetector: smoke alarm marker
    val Route = Color(0xFF66E08A) // design-style: fixed-figma-color route: route dots
    val FlameMid = Color(0xFFFFA21F) // design-style: fixed-figma-color flameMid
    val FlameCore = Color(0xFFFFE27A) // design-style: fixed-figma-color flameCore
    val Spray = Color(0xFFFFFFFF) // design-style: fixed-figma-color spray: white at 30 % (material opacity)
    val SmokePuff = Color(0xFF9AA0A6) // design-style: fixed-figma-color smokePuff: grey at 60 %
    val PlateInk = Color(0xFF1C1F24) // design-style: fixed-figma-color plateInk: dark faces (extinguisher handle, nozzle)
    val Ink = Color(0xFF15181D) // design-style: fixed-figma-color ink: text on the interaction / error / primary fills
    val Pole = Color(0xFFFFFFFF) // design-style: fixed-figma-color pole: marker poles (labelPrimaryLight)
    val Gauge = Color(0xFFF4F4F4) // design-style: fixed-figma-color gauge: pressure gauge face
}
