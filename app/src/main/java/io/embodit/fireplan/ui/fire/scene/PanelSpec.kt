package io.embodit.fireplan.ui.fire.scene

/** Ids and dp sizes of every attachment panel (design `panels`); the Compose side declares exactly these, once. */
object PanelSpec {
    const val MAIN = "mainPanel"
    const val DRILL = "drillCard"
    const val RESULT = "resultPanel"
    const val STRIP = "markerStrip"
    const val DETECTOR = "detectorCard"
    const val PAUSE = "pauseMenu"
    const val MENU = "moreMenu"
    const val DIALOG = "dialogPanel"
    const val SNACK = "snackPanel"
    const val COACH = "coachPanel"

    /** Width / height in dp. */
    val MAIN_SIZE = 400f to 300f
    val DRILL_SIZE = 280f to 120f
    val RESULT_SIZE = 520f to 440f
    val STRIP_SIZE = 232f to 64f
    val DETECTOR_SIZE = 296f to 176f
    val PAUSE_SIZE = 380f to 128f
    val MENU_SIZE = 400f to 256f
    val DIALOG_SIZE = 480f to 280f
    val SNACK_SIZE = 560f to 72f
    val COACH_SIZE = 360f to 80f

    /** The main panel family and the menus beside it are shown at 0.75 (1 dp = 0.6 mm). */
    const val MAIN_SCALE = 0.75f

    /** Dialog and snackbar: 0.9 m from the head, shown at 1.125. */
    const val FLOATING_SCALE = 1.125f
    const val FLOATING_DISTANCE_M = 0.9f

    val pool: List<String> = listOf(MAIN, DRILL, RESULT, STRIP, DETECTOR, PAUSE, MENU, DIALOG, SNACK, COACH)

    fun size(id: String): Pair<Float, Float> =
        when (id) {
            MAIN -> MAIN_SIZE
            DRILL -> DRILL_SIZE
            RESULT -> RESULT_SIZE
            STRIP -> STRIP_SIZE
            DETECTOR -> DETECTOR_SIZE
            PAUSE -> PAUSE_SIZE
            MENU -> MENU_SIZE
            DIALOG -> DIALOG_SIZE
            SNACK -> SNACK_SIZE
            else -> COACH_SIZE
        }

    /** Half width / height of a panel in metres at [scale]. */
    fun halfM(id: String, scale: Float): Pair<Float, Float> {
        val (w, h) = size(id)
        return (w * PanelRig.DP_M * scale / 2f) to (h * PanelRig.DP_M * scale / 2f)
    }
}
