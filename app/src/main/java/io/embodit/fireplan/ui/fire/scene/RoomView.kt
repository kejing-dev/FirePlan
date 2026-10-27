package io.embodit.fireplan.ui.fire.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.fireplan.domain.model.Quat4
import io.embodit.fireplan.domain.model.RoomBox
import io.embodit.fireplan.domain.model.RoomSource
import io.embodit.fireplan.domain.model.Vec3

/**
 * The room box (v0.1.1): its twelve edges as thin white bars at 45 %, a faint ceiling (so that there
 * is something to point the smoke alarm at), and a faint floor when the floor is virtual. One shared
 * unit-cube mesh scaled per piece; the tap targets are the room planes' colliders, not these.
 */
internal class RoomView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {

    private val edges = ArrayList<Entity>()
    private var ceiling: Entity? = null
    private var floor: Entity? = null
    private var mesh: MeshResource? = null
    private var visible = false
    private var room: RoomBox? = null
    private var source: RoomSource? = null

    fun prepare() {
        val m = shared.keep(MeshFactory.box(Vec3(1f, 1f, 1f)))
        mesh = m
        for (i in 0 until 12) {
            edges += ModelEntity(m, materials.roomEdge).also {
                it.named("room_edge$i")
                root.addChild(it)
                it.enabled = false
            }
        }
        ceiling = ModelEntity(m, materials.roomFill).also {
            it.named("room_ceiling_fill")
            root.addChild(it)
            it.enabled = false
        }
        floor = ModelEntity(m, materials.roomFill).also {
            it.named("room_floor_fill")
            root.addChild(it)
            it.enabled = false
        }
    }

    /** Idempotent: lays the pieces out for [box] (null hides everything). */
    fun sync(box: RoomBox?, src: RoomSource?) {
        if (box == room && src == source) return
        room = box
        source = src
        if (box == null) {
            apply()
            return
        }
        val rot = Quat4.fromBasis(box.right, Vec3.UP, -box.forward)
        val base = Vec3(box.center.x, box.floorY, box.center.z)
        val w = box.width
        val l = box.length
        val h = box.height
        fun put(e: Entity, local: Vec3, scale: Vec3) = e.setLocal(position = base + rot.rotate(local), rotation = rot, scale = scale)
        var i = 0
        for (y in floatArrayOf(EDGE_LIFT_M, h - EDGE_LIFT_M)) {
            for (s in floatArrayOf(-1f, 1f)) {
                put(edges[i++], Vec3(0f, y, s * l / 2f), Vec3(w, EDGE_M, EDGE_M))
                put(edges[i++], Vec3(s * w / 2f, y, 0f), Vec3(EDGE_M, EDGE_M, l))
            }
        }
        for (sx in floatArrayOf(-1f, 1f)) for (sz in floatArrayOf(-1f, 1f)) put(edges[i++], Vec3(sx * w / 2f, h / 2f, sz * l / 2f), Vec3(EDGE_M, h, EDGE_M))
        ceiling?.let { put(it, Vec3(0f, h - FILL_M, 0f), Vec3(w, FILL_M, l)) }
        floor?.let { put(it, Vec3(0f, FILL_M, 0f), Vec3(w, FILL_M, l)) }
        apply()
    }

    fun setVisible(on: Boolean) {
        if (visible == on) return
        visible = on
        apply()
    }

    private fun apply() {
        val on = visible && room != null
        for (e in edges) e.enabled = on
        ceiling?.enabled = on
        floor?.enabled = on && source == RoomSource.VIRTUAL
    }

    fun clear() {
        edges.forEach { runCatching { it.destroy(true) } }
        edges.clear()
        runCatching { ceiling?.destroy(true) }
        runCatching { floor?.destroy(true) }
        ceiling = null
        floor = null
        room = null
        source = null
    }

    private companion object {
        const val EDGE_M = 0.012f
        const val EDGE_LIFT_M = 0.006f
        const val FILL_M = 0.004f
    }
}
