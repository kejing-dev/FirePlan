package io.embodit.fireplan.ui.fire.scene

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.fireplan.R
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.Quat4
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.domain.usecase.FacePanelUseCase
import io.embodit.fireplan.domain.usecase.PlateUv
import io.embodit.fireplan.platform.FireLog
import io.embodit.fireplan.ui.fire.MarkerUi
import io.embodit.fireplan.ui.theme.FireColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * The markers in the room (design `entities3d.marker`): a ground disc of 0.30 m (type colour at 40 %
 * with a solid edge), a white pole of 0.9 m and an icon plate of 0.12 m that always faces the user.
 * A fire spot on a table top has no pole and its plate 0.25 m above the table; a detector hangs from
 * the ceiling (disc on the ceiling, a 0.15 m stem down, the plate below) or, as the fallback, sits on
 * a wall. The plate carries the tap target (`marker:<id>`). At most 12 markers, 4 primitives each.
 */
internal class MarkerViews(
    private val root: Entity,
    private val materials: SceneMaterials,
    private val shared: SharedResources,
    private val context: Context?,
) {
    private class View(val id: String, val kind: MarkerKind, val group: Entity, val plate: Entity, val tap: Entity, val selection: Entity, var exitIndex: Int) {
        var position = Vec3.ZERO
        var platePos = Vec3.ZERO
        var lastYaw: Float? = null
        var scale = 1f
        var alarmOn = false
    }

    private val views = LinkedHashMap<String, View>()
    private val idByTap = HashMap<Long, String>()
    private val facing = FacePanelUseCase()
    private val plateMaterials = HashMap<String, Material>()

    private var discMesh: MeshResource? = null
    private var edgeMesh: MeshResource? = null
    private var poleMesh: MeshResource? = null
    private var plateMesh: MeshResource? = null
    private var ceilingDiscMesh: MeshResource? = null
    private var selectionMesh: MeshResource? = null

    fun prepare() {
        discMesh = shared.keep(MeshFactory.disc(DISC_R, "mk_disc"))
        edgeMesh = shared.keep(MeshFactory.ring(DISC_R - EDGE_W, DISC_R + 0.001f, "mk_edge"))
        poleMesh = shared.keep(MeshFactory.cylinder(POLE_R, 1f, "mk_pole", segments = 10))
        plateMesh = shared.keep(MeshFactory.discUv(PLATE_R, "mk_plate", segments = 36))
        ceilingDiscMesh = shared.keep(MeshFactory.disc(PLATE_R, "mk_ceiling", segments = 32))
        selectionMesh = shared.keep(MeshFactory.ring(DISC_R + 0.02f, DISC_R + 0.035f, "mk_selection", dashes = 16, dashFill = 0.55f))
    }

    val count: Int
        get() = views.size

    fun markerIdOf(entity: Entity): String? = idByTap[entity.id]

    /** Stage position of the icon plate (where strips and cards hang from). */
    fun platePosition(id: String): Vec3? = views[id]?.platePos

    fun position(id: String): Vec3? = views[id]?.position

    /**
     * The marker whose icon plate the ray passes closest to (within [PICK_R]), nearest first; null when none.
     * v0.1.1: the controller ray picks a marker to drag (taste profile D14).
     */
    fun pick(origin: Vec3, direction: Vec3): String? {
        var best: String? = null
        var bestT = Float.MAX_VALUE
        for (v in views.values) {
            val to = v.platePos - origin
            val t = to.dot(direction)
            if (t <= 0f) continue
            val miss = (to - direction * t).length
            if (miss <= PICK_R && t < bestT) {
                bestT = t
                best = v.id
            }
        }
        return best
    }

    /** Moves a marker's entities while it is dragged; the next [sync] puts it where the model says. */
    fun moveTo(id: String, position: Vec3) {
        val v = views[id] ?: return
        if (v.position == position) return
        v.platePos = position + (v.platePos - v.position)
        v.position = position
        v.group.setLocal(position = position)
    }

    /** Idempotent diff of the markers against the entities. */
    fun sync(markers: List<MarkerUi>, selectedId: String?) {
        val wanted = markers.associateBy { it.id }
        val gone = views.keys.filter { it !in wanted }
        for (id in gone) remove(id)
        for (m in markers) {
            val v = views[m.id]
            if (v == null) {
                add(m)
            } else if (v.position != m.position || v.exitIndex != m.exitIndex) {
                v.position = m.position
                v.group.setLocal(position = m.position)
                if (v.exitIndex != m.exitIndex) {
                    v.exitIndex = m.exitIndex
                    v.plate.setMaterial(plateMaterial(m.kind, m.exitIndex, alarm = false))
                }
                v.platePos = m.position + plateOffset(m)
            }
            views[m.id]?.selection?.enabled = m.id == selectedId
        }
    }

    private fun plateOffset(m: MarkerUi): Vec3 =
        when {
            m.kind == MarkerKind.DETECTOR && m.onCeiling -> Vec3(0f, -STEM_M - PLATE_R, 0f)
            m.kind == MarkerKind.DETECTOR -> Vec3(0f, -0.02f, 0f)
            m.kind == MarkerKind.FIRE && m.onTable -> Vec3(0f, TABLE_PLATE_M, 0f)
            else -> Vec3(0f, POLE_M + PLATE_R, 0f)
        }

    private fun add(m: MarkerUi) {
        val disc = discMesh ?: return
        val edge = edgeMesh ?: return
        val pole = poleMesh ?: return
        val plateM = plateMesh ?: return
        val group = Entity().named("marker:${m.id}")
        root.addChild(group)
        group.setLocal(position = m.position)
        val (discMat, edgeMat) = discMaterials(m.kind)
        if (m.kind == MarkerKind.DETECTOR && m.onCeiling) {
            // ceiling disc faces down, a short stem hangs from it
            val d = ModelEntity(ceilingDiscMesh ?: disc, edgeMat)
            d.setLocal(position = Vec3(0f, -0.001f, 0f), rotation = Quat4.axisAngle(Vec3.RIGHT, PI.toFloat()))
            group.addChild(d)
            val stem = ModelEntity(pole, materials.white)
            stem.setLocal(position = Vec3(0f, -STEM_M, 0f), scale = Vec3(1f, STEM_M, 1f))
            group.addChild(stem)
        } else if (m.kind == MarkerKind.DETECTOR) {
            // wall fallback: a small disc on the wall and a stem towards the room is not known; the plate sits just below
            val d = ModelEntity(ceilingDiscMesh ?: disc, edgeMat)
            d.setLocal(position = Vec3(0f, 0.001f, 0f))
            group.addChild(d)
        } else {
            val d = ModelEntity(disc, discMat)
            d.setLocal(position = Vec3(0f, 0.002f, 0f))
            group.addChild(d)
            val e = ModelEntity(edge, edgeMat)
            e.setLocal(position = Vec3(0f, 0.0025f, 0f))
            group.addChild(e)
            if (!(m.kind == MarkerKind.FIRE && m.onTable)) {
                val p = ModelEntity(pole, materials.white)
                p.setLocal(scale = Vec3(1f, POLE_M, 1f))
                group.addChild(p)
            }
        }
        val plate = ModelEntity(plateM, plateMaterial(m.kind, m.exitIndex, alarm = false))
        val off = plateOffset(m)
        plate.setLocal(position = off)
        group.addChild(plate)
        // the tap target: a box around the plate, named for the tap handler
        val tap = Entity().named("marker:${m.id}")
        tap.tapBox(Vec3(PLATE_R * 2.4f, PLATE_R * 2.4f, 0.05f))
        plate.addChild(tap)
        idByTap[tap.id] = m.id
        val sel = ModelEntity(selectionMesh ?: edge, materials.selection)
        sel.setLocal(position = Vec3(0f, 0.004f, 0f))
        sel.enabled = false
        group.addChild(sel)
        val v = View(m.id, m.kind, group, plate, tap, sel, m.exitIndex)
        v.position = m.position
        v.platePos = m.position + off
        views[m.id] = v
        FireLog.i("MARKER view add id=${m.id} kind=${m.kind.tag} pos=${FireLog.pos(m.position)}")
    }

    private fun remove(id: String) {
        val v = views.remove(id) ?: return
        idByTap.remove(v.tap.id)
        runCatching { v.group.destroy(true) }
    }

    private fun discMaterials(kind: MarkerKind): Pair<Material, Material> =
        when (kind) {
            MarkerKind.FIRE -> materials.discFire to materials.edgeFire
            MarkerKind.EXTINGUISHER -> materials.discExt to materials.edgeExt
            MarkerKind.EXIT -> materials.discExit to materials.edgeExit
            MarkerKind.DETECTOR -> materials.discDet to materials.edgeDet
        }

    /** Scale of a marker's plate (the evacuation target exit is shown at 1.2). */
    fun setEmphasis(id: String, scale: Float) {
        val v = views[id] ?: return
        if (v.scale == scale) return
        v.scale = scale
        v.plate.setUniformScale(scale)
    }

    /** The detector the alarm sounds from blinks interaction yellow at 1 Hz. */
    fun setAlarm(id: String?, nowMs: Long) {
        for (v in views.values) {
            val on = v.id == id && ((nowMs / 500L) % 2L == 0L)
            if (on != v.alarmOn) {
                v.alarmOn = on
                v.plate.setMaterial(plateMaterial(v.kind, v.exitIndex, alarm = on))
            }
        }
    }

    /** Every plate turns towards the head (yaw and pitch, no roll). */
    /** Plates turn about the vertical only, front to the head, never pitched or mirrored (v0.1.2, taste profile D10). */
    fun onFrame(head: Vec3) {
        for (v in views.values) {
            val (rotation, yaw) = PlateUv.yawTowards(v.platePos, head, v.lastYaw)
            if (v.lastYaw != null && kotlin.math.abs(yaw - v.lastYaw!!) < 1e-4f) continue
            v.lastYaw = yaw
            v.plate.setLocal(rotation = rotation)
        }
    }

    /**
     * Debug `platetest on`: every plate shows "F" with an arrow up, which is wrong-looking under any mirror or flip,
     * to check the texture orientation on the device (v0.1.2). Off = the icons again.
     */
    var testPattern = false
        set(value) {
            if (field == value) return
            field = value
            plateMaterials.clear()
            for (v in views.values) v.plate.setMaterial(plateMaterial(v.kind, v.exitIndex, alarm = v.alarmOn))
        }

    fun clear() {
        views.values.forEach { runCatching { it.group.destroy(true) } }
        views.clear()
        idByTap.clear()
    }

    private fun plateMaterial(kind: MarkerKind, exitIndex: Int, alarm: Boolean): Material {
        val key = "${kind.tag}:$exitIndex:$alarm"
        return plateMaterials.getOrPut(key) {
            val fill =
                if (alarm) FireColors.Interaction
                else when (kind) {
                    MarkerKind.FIRE -> FireColors.MarkerFire
                    MarkerKind.EXTINGUISHER -> FireColors.MarkerExtinguisher
                    MarkerKind.EXIT -> FireColors.MarkerExit
                    MarkerKind.DETECTOR -> FireColors.MarkerDetector
                }
            val bitmap = plateBitmap(kind, exitIndex, fill, if (alarm) FireColors.Ink else FireColors.LabelPrimaryLight)
            if (bitmap != null) materials.textured(bitmap, fill) else materials.textured(solidBitmap(fill), fill)
        }
    }

    private fun solidBitmap(fill: Color): Bitmap {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        b.eraseColor(fill.toArgb())
        return b
    }

    /** A circle of the type colour with the white vector icon (and the exit number) in it. */
    private fun plateBitmap(kind: MarkerKind, exitIndex: Int, fill: Color, ink: Color): Bitmap? {
        val ctx = context ?: return null
        return try {
            val size = TEX_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(fill.toArgb())
            if (testPattern) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = ink.toArgb()
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = size * 0.56f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("F↑", size * 0.5f, size * 0.70f, paint)
                return bitmap
            }
            val res =
                when (kind) {
                    MarkerKind.FIRE -> R.drawable.mk_fire
                    MarkerKind.EXTINGUISHER -> R.drawable.mk_extinguisher
                    MarkerKind.EXIT -> R.drawable.mk_exit
                    MarkerKind.DETECTOR -> R.drawable.mk_detector
                }
            val drawable = ctx.getDrawable(res)?.mutate()
            if (drawable != null) {
                drawable.setTint(ink.toArgb())
                val icon = (size * 0.62f).toInt()
                val left = if (kind == MarkerKind.EXIT && exitIndex > 0) (size * 0.08f).toInt() else (size - icon) / 2
                val top = (size - icon) / 2
                drawable.setBounds(left, top, left + icon, top + icon)
                drawable.draw(canvas)
            }
            if (kind == MarkerKind.EXIT && exitIndex > 0) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = ink.toArgb()
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = size * 0.34f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(exitIndex.toString(), size * 0.78f, size * 0.62f, paint)
            }
            bitmap
        } catch (t: Throwable) {
            FireLog.w("MARKER plate bitmap failed: $t")
            null
        }
    }

    companion object {
        const val DISC_R = 0.15f
        const val EDGE_W = 0.006f
        const val POLE_R = 0.004f
        const val POLE_M = 0.9f
        const val PLATE_R = 0.06f
        const val PICK_R = 0.15f
        const val STEM_M = 0.15f
        const val TABLE_PLATE_M = 0.25f
        private const val TEX_PX = 128

        /** Plates pulse 0.9 - 1.1 for an exit without a route (evacuation). */
        fun pulse(nowMs: Long): Float = 1f + 0.1f * sin(2f * PI.toFloat() * (nowMs % 1000L) / 1000f)
    }
}
