package io.embodit.fireplan.ui.fire.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.Vec3

/**
 * The placement preview (v0.1.1, taste profile D1): a see-through marker that follows where the controller ray
 * meets the room, at the spot the placement rules would put the selected kind; the trigger / pinch places it.
 * A floor kind shows its ground disc and a pole at 45 %; a ceiling detector a disc on the ceiling.
 */
internal class GhostView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {

    private var group: Entity? = null
    private var disc: ModelEntity? = null
    private var pole: ModelEntity? = null
    private var kind: MarkerKind? = null
    private var shown = false

    fun prepare() {
        val g = Entity().named("ghost")
        root.addChild(g)
        val d = ModelEntity(shared.keep(MeshFactory.disc(MarkerViews.DISC_R, "ghost_disc")), materials.discFire).also { it.named("ghost_disc") }
        val p = ModelEntity(shared.keep(MeshFactory.cylinder(MarkerViews.POLE_R * 1.5f, MarkerViews.POLE_M, "ghost_pole", segments = 8)), materials.roomEdge).also { it.named("ghost_pole") }
        g.addChild(d)
        g.addChild(p)
        d.setLocal(position = Vec3(0f, LIFT_M, 0f))
        g.enabled = false
        group = g
        disc = d
        pole = p
    }

    /** Shows the preview of [k] at [position] ([onCeiling]: a detector on the ceiling, no pole). */
    fun show(k: MarkerKind, position: Vec3, onCeiling: Boolean) {
        val g = group ?: return
        if (k != kind) {
            kind = k
            disc?.setMaterial(materialOf(k))
        }
        disc?.setLocal(position = Vec3(0f, if (onCeiling) -LIFT_M else LIFT_M, 0f))
        pole?.enabled = !onCeiling && k != MarkerKind.DETECTOR
        g.setLocal(position = position)
        if (!shown) {
            g.enabled = true
            shown = true
        }
    }

    fun hide() {
        if (!shown) return
        group?.enabled = false
        shown = false
    }

    fun destroy() {
        runCatching { group?.destroy(true) }
        group = null
        disc = null
        pole = null
        shown = false
    }

    private fun materialOf(k: MarkerKind): Material =
        when (k) {
            MarkerKind.FIRE -> materials.discFire
            MarkerKind.EXTINGUISHER -> materials.discExt
            MarkerKind.EXIT -> materials.discExit
            MarkerKind.DETECTOR -> materials.discDet
        }

    private companion object {
        const val LIFT_M = 0.004f
    }
}
