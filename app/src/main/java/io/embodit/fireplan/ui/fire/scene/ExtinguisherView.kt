package io.embodit.fireplan.ui.fire.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import io.embodit.fireplan.domain.model.Quat4
import io.embodit.fireplan.domain.model.Vec3
import kotlin.math.PI

/**
 * The extinguisher prop (design `entities3d.extinguisher`): a red cylinder of 0.10 x 0.30 m with a
 * black lever, a yellow pin ring (the tap target `extPin`), a black nozzle and a small pressure gauge
 * whose needle follows what is left. It rides at the lower right of the view (lazy follow) and fades
 * out in 0.3 s when the evacuation starts. While spraying, a white see-through cone runs from the
 * nozzle to the aim. At most 8 primitives plus the cone.
 */
internal class ExtinguisherView(private val root: Entity, private val materials: SceneMaterials, private val shared: SharedResources) {

    private val mount = Entity().named("extinguisher")
    private val pinRing = Entity().named("extPin")
    private var pinModel: ModelEntity? = null
    private var needle: Strip? = null
    private var cone: ModelEntity? = null
    private var shown = false
    private var fadeFrom = 0f
    private var fadeTo = 0f
    private var fadeStartMs = NEVER
    private var opacity = 0f
    private var pose = FollowPose(Vec3.ZERO, 0f)

    init {
        root.addChild(mount)
        mount.enabled = false
    }

    fun prepare() {
        val body = ModelEntity(shared.keep(MeshFactory.cylinder(BODY_R, BODY_H, "ext_body", segments = 18)), materials.red)
        body.setLocal(position = Vec3(0f, -BODY_H / 2f, 0f))
        mount.addChild(body)
        // neck and lever on top
        val neck = ModelEntity(shared.keep(MeshFactory.cylinder(0.014f, 0.035f, "ext_neck", segments = 10)), materials.ink)
        neck.setLocal(position = Vec3(0f, BODY_H / 2f, 0f))
        mount.addChild(neck)
        val lever = ModelEntity(shared.keep(MeshFactory.box(Vec3(0.012f, 0.008f, 0.075f))), materials.ink)
        lever.setLocal(position = Vec3(0f, BODY_H / 2f + 0.045f, -0.02f), rotation = Quat4.axisAngle(Vec3.RIGHT, -0.25f))
        mount.addChild(lever)
        // the pin ring: a yellow torus standing on the neck, with a sphere collider
        val ringMesh: MeshResource = runCatching { MeshResource.createTorus(0.02f, 0.0035f) }.getOrElse { MeshFactory.ring(0.016f, 0.022f, "ext_pin_ring") }
        val pin = ModelEntity(shared.keep(ringMesh), materials.interaction)
        pin.setLocal(position = Vec3(0.02f, BODY_H / 2f + 0.03f, 0f), rotation = Quat4.axisAngle(Vec3(0f, 0f, 1f), PI.toFloat() / 2f))
        mount.addChild(pin)
        pinModel = pin
        pinRing.components.set(CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(0.03f)), physicsMaterial = PhysicsMaterialResource()))
        pinRing.components.set(InteractableComponent())
        pinRing.setLocal(position = Vec3(0.02f, BODY_H / 2f + 0.03f, 0f))
        mount.addChild(pinRing)
        // nozzle: a short black tube pointing ahead (-Z of the prop frame)
        val nozzle = ModelEntity(shared.keep(MeshFactory.cylinder(0.008f, NOZZLE_L, "ext_nozzle", segments = 10)), materials.ink)
        nozzle.setLocal(position = Vec3(-0.03f, BODY_H / 2f - 0.01f, -0.02f), rotation = Quat4.axisAngle(Vec3.RIGHT, -PI.toFloat() / 2f))
        mount.addChild(nozzle)
        // gauge on the front face with its needle
        val gauge = ModelEntity(shared.keep(MeshFactory.discUv(GAUGE_R, "ext_gauge", segments = 24)), materials.gauge)
        gauge.setLocal(position = Vec3(0f, BODY_H / 2f - 0.04f, BODY_R + 0.001f))
        mount.addChild(gauge)
        needle = Strip(mount, shared.keep(MeshFactory.unitBand("ext_needle")), materials.ink, "ext_needle")
        setPressure(1f)
        // spray cone, drawn in Stage space below the root
        val c = ModelEntity(shared.keep(MeshFactory.cone("spray_cone")), materials.spray)
        c.enabled = false
        root.addChild(c)
        cone = c
    }

    fun isPin(entity: Entity): Boolean = entity.id == pinRing.id

    /** Where the nozzle's mouth is, Stage metres. */
    fun nozzleTip(): Vec3 = pose.position + pose.rotation.rotate(Vec3(-0.03f, BODY_H / 2f - 0.01f, -0.02f - NOZZLE_L))

    fun place(pose: FollowPose) {
        this.pose = pose
        mount.setLocal(position = pose.position, rotation = pose.rotation)
    }

    /** Fades in (0.2 s) or out (0.3 s); the prop is disabled once fully faded out. */
    fun setShown(on: Boolean, nowMs: Long) {
        if (on == shown) return
        shown = on
        fadeFrom = opacity
        fadeTo = if (on) 1f else 0f
        fadeStartMs = nowMs
        if (on) {
            mount.enabled = true
            pinRing.enabled = true
        }
    }

    fun setPinHighlight(on: Boolean) {
        pinModel?.setMaterial(if (on) materials.interaction else materials.ink)
    }

    fun setPressure(share: Float) {
        val n = needle ?: return
        val a = (PI.toFloat() * 0.85f) * (1f - share.coerceIn(0f, 1f)) + PI.toFloat() * 0.075f
        val center = Vec3(0f, BODY_H / 2f - 0.04f, BODY_R + 0.0015f)
        val tip = center + Vec3(-kotlin.math.cos(a) * GAUGE_R * 0.8f, kotlin.math.sin(a) * GAUGE_R * 0.8f, 0f)
        n.draw(center, tip, 0.002f, Vec3(0f, 0f, 1f))
    }

    /** The spray cone from the nozzle to [target] (Stage metres), or hidden. */
    fun spray(target: Vec3?) {
        val c = cone ?: return
        if (target == null || !shown) {
            c.enabled = false
            return
        }
        val from = nozzleTip()
        val d = target - from
        val len = d.length
        if (len < 0.05f) {
            c.enabled = false
            return
        }
        val z = d / len
        var x = Vec3.UP.cross(z)
        if (x.length < 1e-3f) x = Vec3.RIGHT
        x = x.normalized()
        val y = z.cross(x).normalized()
        val radius = 0.03f + 0.09f * len
        c.setLocal(position = from, rotation = Quat4.fromBasis(x, y, z), scale = Vec3(radius, radius, len))
        c.enabled = true
    }

    fun onFrame(nowMs: Long) {
        if (fadeStartMs == NEVER) return
        val ms = if (fadeTo > fadeFrom) FADE_IN_MS else FADE_OUT_MS
        val q = ((nowMs - fadeStartMs) / ms).coerceIn(0f, 1f)
        opacity = fadeFrom + (fadeTo - fadeFrom) * q
        mount.setOpacity(opacity)
        if (q >= 1f) {
            fadeStartMs = NEVER
            if (fadeTo <= 0f) {
                mount.enabled = false
                cone?.enabled = false
            }
        }
    }

    fun destroy() {
        runCatching { mount.destroy(true) }
        runCatching { cone?.destroy(true) }
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE
        const val BODY_R = 0.05f
        const val BODY_H = 0.30f
        private const val NOZZLE_L = 0.06f
        private const val GAUGE_R = 0.016f
        private const val FADE_IN_MS = 200f
        private const val FADE_OUT_MS = 300f

        /** Half sizes of the prop's bounding box for the surface rule. */
        val HALF_EXTENTS = listOf(0.06f, 0.2f, 0.06f)
    }
}
