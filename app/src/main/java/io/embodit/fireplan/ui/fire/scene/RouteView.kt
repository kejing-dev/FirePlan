package io.embodit.fireplan.ui.fire.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.fireplan.domain.model.Vec3
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The green route dots (design `entities3d.routeDots`, the signature element): floor discs of 6 cm,
 * 0.4 m apart, passable green at 60 %. Shown while setting up and while evacuating; while evacuating
 * the six dots ahead of the user light up to 100 % and pulse forward every 0.6 s (colour / opacity
 * only, no particles). At most 2 routes x 64 points, one shared mesh, a pool of entities.
 */
internal class RouteView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {

    private class Dot(val entity: ModelEntity) {
        var lit = false
        var opacity = -1f
    }

    private val pool = ArrayList<Dot>()
    private var mesh: MeshResource? = null
    private val routeDots = LinkedHashMap<String, List<Int>>()
    private val points = LinkedHashMap<String, List<Vec3>>()
    private var visible = false
    private var used = 0

    fun prepare() {
        mesh = shared.keep(MeshFactory.disc(DOT_R, "route_dot", segments = 16))
    }

    /** Idempotent: lays the dots of [routes] out on the pool. */
    fun sync(routes: Map<String, List<Vec3>>, floorY: Float) {
        val m = mesh ?: return
        points.clear()
        routeDots.clear()
        var next = 0
        for ((exitId, pts) in routes) {
            val capped = pts.take(MAX_POINTS)
            points[exitId] = capped
            val ids = ArrayList<Int>(capped.size)
            for (p in capped) {
                if (next >= MAX_DOTS) break
                val dot =
                    if (next < pool.size) pool[next]
                    else {
                        val e = ModelEntity(m, materials.routeDim)
                        e.named("route_dot$next")
                        root.addChild(e)
                        Dot(e).also { pool += it }
                    }
                dot.entity.setLocal(position = Vec3(p.x, floorY + LIFT_M, p.z))
                dot.entity.enabled = visible
                ids += next
                next++
            }
            routeDots[exitId] = ids
        }
        used = next
        for (i in next until pool.size) pool[i].entity.enabled = false
        unlightAll()
    }

    fun setVisible(on: Boolean) {
        if (visible == on) return
        visible = on
        for (i in 0 until used) pool[i].entity.enabled = on
        if (!on) unlightAll()
    }

    val isEmpty: Boolean
        get() = routeDots.isEmpty()

    /** The route whose nearest point is closest to [foot], and that point's index. */
    fun nearest(foot: Vec3): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        var bestD = Float.MAX_VALUE
        for ((exitId, pts) in points) {
            for (i in pts.indices) {
                val d = hypot(pts[i].x - foot.x, pts[i].z - foot.z)
                if (d < bestD) {
                    bestD = d
                    best = exitId to i
                }
            }
        }
        return best
    }

    /** Lights the six dots ahead of index [from] on route [exitId] with the forward pulse; others stay dim. */
    fun light(exitId: String?, from: Int, nowMs: Long) {
        val ids = exitId?.let { routeDots[it] }
        for ((rid, dots) in routeDots) {
            for ((k, idx) in dots.withIndex()) {
                val dot = pool[idx]
                val lit = ids != null && rid == exitId && k >= from && k < from + AHEAD
                if (lit != dot.lit) {
                    dot.lit = lit
                    dot.entity.setMaterial(if (lit) materials.routeLit else materials.routeDim)
                    if (!lit) {
                        dot.entity.setOpacity(1f)
                        dot.opacity = 1f
                    }
                }
                if (lit) {
                    val phase = (nowMs % PULSE_MS) / PULSE_MS.toFloat()
                    val o = 0.65f + 0.35f * sin(2f * PI.toFloat() * (phase - (k - from).toFloat() / AHEAD))
                    if (kotlin.math.abs(o - dot.opacity) > 0.03f) {
                        dot.opacity = o
                        dot.entity.setOpacity(o)
                    }
                }
            }
        }
    }

    fun unlightAll() {
        for (i in 0 until used) {
            val dot = pool[i]
            if (dot.lit) {
                dot.lit = false
                dot.entity.setMaterial(materials.routeDim)
                dot.entity.setOpacity(1f)
                dot.opacity = 1f
            }
        }
    }

    fun clear() {
        pool.forEach { runCatching { it.entity.destroy(true) } }
        pool.clear()
        routeDots.clear()
        points.clear()
        used = 0
    }

    companion object {
        const val DOT_R = 0.03f
        const val LIFT_M = 0.003f
        const val MAX_POINTS = 64
        const val MAX_DOTS = 128
        const val AHEAD = 6
        private const val PULSE_MS = 600L
    }
}
