package io.embodit.fireplan.ui.fire.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.fireplan.domain.model.DrillRules
import io.embodit.fireplan.domain.model.PointerRay
import io.embodit.fireplan.domain.model.Quat4
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.ui.fire.AimTarget
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The flame (design `entities3d.flame`, lightweight by rule A6): three low-poly teardrops stacked
 * concentrically (outer orange 85 %, middle, core), self-lit, each scaled in y by 0.9 - 1.1 at 2.5 Hz
 * with the phases apart and swayed by 4 degrees. No particles, no smoke, no light. Three sizes;
 * level changes scale over 0.4 s. Going out: 0.6 s shrink to nothing and one grey puff that rises
 * for 0.8 s. The base ring (dashed, interaction yellow at 70 %) marks the aiming target; two unseen
 * colliders (`fireBase`, `fireTop`) tell "aimed at the base" from "aimed at the flames".
 */
internal class FlameView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {

    private val flameRoot = Entity().named("flame")
    private val layers = ArrayList<ModelEntity>(3)
    private var ring: ModelEntity? = null
    private var puff: ModelEntity? = null
    private val baseCollider = Entity().named("fireBase")
    private val topCollider = Entity().named("fireTop")
    private var teardrop: MeshResource? = null
    private var ringMesh: MeshResource? = null

    var position: Vec3 = Vec3.ZERO
        private set
    var level: Int = 1
        private set
    private var shown = false
    private var fromH = 0f
    private var fromW = 0f
    private var toH = 0f
    private var toW = 0f
    private var levelStartMs = 0L
    private var outStartMs = NEVER
    private var frozenMs = NEVER
    private var frozenAt = 0L
    private var ringShown = false
    private var ringStyle = RingStyle.DASHED

    enum class RingStyle { DASHED, SOLID, ALERT }

    init {
        root.addChild(flameRoot)
        flameRoot.enabled = false
    }

    fun prepare() {
        val mesh = shared.keep(MeshFactory.teardrop("flame_drop"))
        teardrop = mesh
        for (m in listOf(materials.flameOuter, materials.flameMid, materials.flameCore)) {
            val e = ModelEntity(mesh, m)
            flameRoot.addChild(e)
            layers += e
        }
        ringMesh = shared.keep(MeshFactory.ring(0.92f, 1f, "fire_ring", dashes = 12, dashFill = 0.6f))
        val r = ModelEntity(ringMesh!!, materials.ringYellow)
        r.enabled = false
        flameRoot.addChild(r)
        ring = r
        val p = ModelEntity(shared.keep(MeshFactory.disc(0.06f, "smoke_puff", segments = 20)), materials.smoke)
        p.enabled = false
        flameRoot.addChild(p)
        puff = p
        flameRoot.addChild(baseCollider)
        flameRoot.addChild(topCollider)
        baseCollider.enabled = false
        topCollider.enabled = false
    }

    val ringRadius: Float
        get() = DrillRules.ringRadius(level)

    fun isBase(entity: Entity): Boolean = entity.id == baseCollider.id

    fun isTop(entity: Entity): Boolean = entity.id == topCollider.id

    /** Shows the flame at [at] with [level]; [immediate] skips the grow transition. */
    fun show(at: Vec3, level: Int, nowMs: Long, immediate: Boolean) {
        position = at
        flameRoot.setLocal(position = at)
        outStartMs = NEVER
        frozenMs = NEVER
        puff?.enabled = false
        if (!shown) {
            shown = true
            flameRoot.enabled = true
            layers.forEach { it.enabled = true }
            fromH = 0f
            fromW = 0f
        } else {
            fromH = currentH(nowMs)
            fromW = currentW(nowMs)
        }
        this.level = level.coerceIn(1, 3)
        toH = DrillRules.FLAME_HEIGHT[this.level - 1]
        toW = DrillRules.FLAME_WIDTH[this.level - 1]
        levelStartMs = if (immediate) nowMs - LEVEL_MS.toLong() else nowMs
        colliders()
    }

    fun setLevel(level: Int, nowMs: Long) {
        if (!shown || level == this.level) return
        show(position, level, nowMs, immediate = false)
    }

    /** The flame goes out: 0.6 s shrink, then the puff. */
    fun extinguish(nowMs: Long) {
        if (!shown || outStartMs != NEVER) return
        outStartMs = nowMs
        fromH = currentH(nowMs)
        fromW = currentW(nowMs)
        baseCollider.enabled = false
        topCollider.enabled = false
        setRing(false)
    }

    fun hide() {
        shown = false
        outStartMs = NEVER
        flameRoot.enabled = false
        puff?.enabled = false
        setRing(false)
    }

    fun setRing(on: Boolean) {
        if (ringShown == on) return
        ringShown = on
        ring?.enabled = on
    }

    fun setRingStyle(style: RingStyle) {
        if (style == ringStyle) return
        ringStyle = style
        ring?.setMaterial(
            when (style) {
                RingStyle.DASHED -> materials.ringYellow
                RingStyle.SOLID -> materials.ringYellowSolid
                RingStyle.ALERT -> materials.ringAlert
            }
        )
    }

    /** Pausing freezes the animation where it is. */
    fun setFrozen(on: Boolean, nowMs: Long) {
        if (on && frozenMs == NEVER) {
            frozenMs = nowMs
        } else if (!on && frozenMs != NEVER) {
            frozenAt += nowMs - frozenMs
            frozenMs = NEVER
        }
    }

    private fun currentH(nowMs: Long): Float {
        val t = ((nowMs - levelStartMs) / LEVEL_MS).coerceIn(0f, 1f)
        return fromH + (toH - fromH) * Ease.outCubic(t)
    }

    private fun currentW(nowMs: Long): Float {
        val t = ((nowMs - levelStartMs) / LEVEL_MS).coerceIn(0f, 1f)
        return fromW + (toW - fromW) * Ease.outCubic(t)
    }

    private fun colliders() {
        val w = toW
        val h = toH
        baseCollider.tapBox(Vec3(w * 1.4f, BASE_H, w * 1.4f), Vec3(0f, BASE_H / 2f, 0f))
        topCollider.tapBox(Vec3(w, (h - BASE_H).coerceAtLeast(0.05f), w), Vec3(0f, BASE_H + (h - BASE_H).coerceAtLeast(0.05f) / 2f, 0f))
        baseCollider.enabled = true
        topCollider.enabled = true
    }

    /** Once per frame: flicker, level growth, going out. */
    fun onFrame(nowMs: Long) {
        if (!shown) return
        val anim = (if (frozenMs != NEVER) frozenMs else nowMs) - frozenAt
        var h = currentH(nowMs)
        var w = currentW(nowMs)
        if (outStartMs != NEVER) {
            val q = ((nowMs - outStartMs) / OUT_MS).coerceIn(0f, 1f)
            val k = 1f - Ease.inOutCubic(q)
            h = fromH * k
            w = fromW * k
            val p = puff
            if (p != null) {
                val pq = ((nowMs - outStartMs - OUT_MS.toLong()) / PUFF_MS).coerceIn(-0.2f, 1f)
                if (pq >= 0f && pq < 1f) {
                    p.enabled = true
                    p.setLocal(position = Vec3(0f, 0.1f + pq * 0.4f, 0f), scale = Vec3(1f + pq, 1f, 1f + pq))
                    p.setOpacity(1f - pq)
                } else if (pq >= 1f) {
                    p.enabled = false
                    layers.forEach { it.enabled = false }
                }
            }
        }
        val t = anim / 1000f
        for (i in layers.indices) {
            val phase = i * 2.1f
            val flick = 1f + 0.1f * sin(2f * PI.toFloat() * FLICKER_HZ * t + phase)
            val sway = SWAY_RAD * sin(2f * PI.toFloat() * SWAY_HZ * t + phase * 0.7f)
            val share = LAYER_SHARE[i]
            val rx = w / 2f * share
            layers[i].setLocal(
                position = Vec3(0f, 0.002f * i, 0f),
                rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), sway) * Quat4.axisAngle(Vec3.UP, phase),
                scale = Vec3(rx, (h * share * flick).coerceAtLeast(0.001f), rx),
            )
        }
        val r = ringRadius
        ring?.setLocal(position = Vec3(0f, 0.004f, 0f), scale = Vec3(r, 1f, r))
    }

    /**
     * What a pointing ray aims at: the base ring (a disc of 1.4 x the ring radius at the base, within
     * 0.25 m above it) or the flames above that, else nothing. Analytic, so it works with the
     * controller ray and the debug hooks alike.
     */
    fun aimOf(ray: PointerRay): AimTarget {
        if (!shown || outStartMs != NEVER) return AimTarget.NONE
        val w = toW
        val h = toH
        // base: hit with the horizontal plane at the base
        val dy = ray.direction.y
        if (abs(dy) > 1e-4f) {
            val t = (position.y - ray.origin.y) / dy
            if (t > 0f) {
                val p = ray.origin + ray.direction * t
                if (hypot(p.x - position.x, p.z - position.z) <= ringRadius * 1.4f) return AimTarget.RING
            }
        }
        // the vertical axis segment of the flame: nearest point between the ray and the axis
        val axisTop = position + Vec3(0f, h, 0f)
        val d = closest(ray, position + Vec3(0f, BASE_H, 0f), axisTop)
        if (d != null && d.first <= w * 0.5f + 0.02f) return AimTarget.TOP
        val b = closest(ray, position, position + Vec3(0f, BASE_H, 0f))
        if (b != null && b.first <= ringRadius * 1.4f) return AimTarget.RING
        return AimTarget.NONE
    }

    /** Where a ray's hit lies sideways of the base, metres along [tangent] (for the sweep count). */
    fun lateralOf(ray: PointerRay, tangent: Vec3): Float {
        val dy = ray.direction.y
        if (abs(dy) < 1e-4f) return 0f
        val t = (position.y - ray.origin.y) / dy
        if (t <= 0f) return 0f
        val p = ray.origin + ray.direction * t
        return (p - position).dot(tangent)
    }

    /** Distance and parameter of the closest approach of a ray to a segment, or null when behind the ray. */
    private fun closest(ray: PointerRay, a: Vec3, b: Vec3): Pair<Float, Float>? {
        val u = ray.direction
        val v = (b - a)
        val vl = v.length
        if (vl < 1e-5f) return null
        val vn = v / vl
        val w0 = ray.origin - a
        val aa = u.dot(u)
        val bb = u.dot(vn)
        val cc = vn.dot(vn)
        val dd = u.dot(w0)
        val ee = vn.dot(w0)
        val den = aa * cc - bb * bb
        if (abs(den) < 1e-6f) return null
        val s = (bb * ee - cc * dd) / den
        var t = (aa * ee - bb * dd) / den
        if (s < 0f) return null
        t = t.coerceIn(0f, vl)
        val p = ray.origin + u * s
        val q = a + vn * t
        return p.distanceTo(q) to t
    }

    fun destroy() {
        runCatching { flameRoot.destroy(true) }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        private const val LEVEL_MS = 400f
        private const val OUT_MS = 600f
        private const val PUFF_MS = 800f
        private const val FLICKER_HZ = 2.5f
        private const val SWAY_HZ = 1.7f

        /** 4 degrees. */
        private const val SWAY_RAD = 0.0698f
        private const val BASE_H = 0.25f
        private val LAYER_SHARE = floatArrayOf(1f, 0.72f, 0.42f)
    }
}
