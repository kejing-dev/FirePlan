package io.embodit.fireplan.domain

import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.RoomBox
import io.embodit.fireplan.domain.model.SurfaceKind
import io.embodit.fireplan.domain.model.Vec3
import io.embodit.fireplan.domain.usecase.PlaceMarkerUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.1: the room box and the never-refusing placement rules (no detected planes at all). */
class RoomPlacementTest {

    private val head = Vec3(0f, 1.6f, 0f)
    private val room = RoomBox.around(head, Vec3.AHEAD)
    private val rule = PlaceMarkerUseCase()

    private fun plane(id: String) = room.planes.first { it.id == id }

    private fun place(kind: MarkerKind, planeId: String, point: Vec3) =
        (rule.place("m", kind, plane(planeId), point, head, room.planes, room.floorY, room.ceilingY) as PlaceMarkerUseCase.Outcome.Place).marker

    @Test
    fun boxAroundViewer() {
        assertEquals(6, room.planes.size)
        assertEquals(1, room.planes.count { it.kind == SurfaceKind.FLOOR })
        assertEquals(1, room.planes.count { it.kind == SurfaceKind.CEILING })
        assertEquals(4, room.planes.count { it.kind == SurfaceKind.WALL })
        assertTrue(room.containsFlat(head))
        assertEquals(2.7f, room.ceilingY, 1e-4f)
        // the viewer stands 1.5 m from the back wall, the front wall is ahead (-Z)
        val front = plane(RoomBox.ID_FRONT).centroid
        assertEquals(-(room.length - 1.5f), front.z, 1e-3f)
    }

    @Test
    fun detectorOnCeilingAnywhere() {
        val m = place(MarkerKind.DETECTOR, RoomBox.ID_CEILING, Vec3(1.2f, 2.7f, -2f))
        assertTrue(m.onCeiling)
        assertEquals(2.7f, m.position.y, 1e-4f)
        assertEquals(1.2f, m.position.x, 1e-4f)
    }

    @Test
    fun detectorFromFloorGoesStraightUp() {
        val m = place(MarkerKind.DETECTOR, RoomBox.ID_FLOOR, Vec3(0.5f, 0f, -1f))
        assertTrue(m.onCeiling)
        assertEquals(room.ceilingY, m.position.y, 1e-4f)
        assertEquals(-1f, m.position.z, 1e-4f)
    }

    @Test
    fun detectorLowOnWallMovesToCeilingAwayFromWall() {
        val front = plane(RoomBox.ID_FRONT).centroid
        val m = place(MarkerKind.DETECTOR, RoomBox.ID_FRONT, Vec3(0f, 1.2f, front.z))
        assertTrue(m.onCeiling)
        assertEquals(front.z + PlaceMarkerUseCase.DETECTOR_IN_FROM_WALL_M, m.position.z, 1e-3f)
        assertEquals(PlaceMarkerUseCase.DETECTOR_IN_FROM_WALL_M, m.wallDistM!!, 1e-3f)
    }

    @Test
    fun detectorHighOnWallIsWallMounted() {
        val front = plane(RoomBox.ID_FRONT).centroid
        val m = place(MarkerKind.DETECTOR, RoomBox.ID_FRONT, Vec3(0f, 2.5f, front.z))
        assertFalse(m.onCeiling)
        assertEquals(0.2f, m.belowCeilingM!!, 1e-3f)
    }

    @Test
    fun exitOnWallLandsAtItsFootInsideTheRoom() {
        val right = plane(RoomBox.ID_RIGHT).centroid
        val m = place(MarkerKind.EXIT, RoomBox.ID_RIGHT, Vec3(right.x, 1.0f, -2f))
        assertEquals(0f, m.position.y, 1e-4f)
        assertEquals(right.x - PlaceMarkerUseCase.DOOR_OFFSET_M, m.position.x, 1e-3f)
        assertTrue(room.containsFlat(m.position))
    }

    @Test
    fun floorKindsFromCeilingGoStraightDown() {
        for (kind in listOf(MarkerKind.EXIT, MarkerKind.EXTINGUISHER, MarkerKind.FIRE)) {
            val m = place(kind, RoomBox.ID_CEILING, Vec3(-1f, 2.7f, -3f))
            assertEquals(0f, m.position.y, 1e-4f)
            assertEquals(-3f, m.position.z, 1e-4f)
        }
    }

    @Test
    fun floorMarkerNeverStandsInTheUser() {
        val m = place(MarkerKind.EXIT, RoomBox.ID_FLOOR, Vec3(0.1f, 0f, -0.2f))
        val d = kotlin.math.hypot(m.position.x - head.x, m.position.z - head.z)
        assertEquals(PlaceMarkerUseCase.HEAD_CLEAR_M, d, 1e-3f)
        // pushed straight out along head -> point
        assertTrue(m.position.x > 0f && m.position.z < 0f)
        val far = place(MarkerKind.EXIT, RoomBox.ID_FLOOR, Vec3(1f, 0f, -2f))
        assertEquals(1f, far.position.x, 1e-4f)
    }

    @Test
    fun resizeKeepsFrontWallAndClamps() {
        val front = plane(RoomBox.ID_FRONT).centroid.z
        val longer = room.resized(dl = 1f)
        assertEquals(front, longer.planes.first { it.id == RoomBox.ID_FRONT }.centroid.z, 1e-3f)
        assertEquals(RoomBox.MAX_H, room.resized(dh = 10f).height, 1e-4f)
        assertEquals(RoomBox.MIN_W, room.resized(dw = -10f).width, 1e-4f)
    }

    @Test
    fun moveAndTurnFollowTheViewer() {
        val moved = room.moved(Vec3.AHEAD, right = 0.25f, ahead = 0.5f, turnDeg = 90f)
        assertEquals(room.center.x + 0.25f, moved.center.x, 1e-4f)
        assertEquals(room.center.z - 0.5f, moved.center.z, 1e-4f)
        assertEquals(90f, moved.yawDeg, 1e-4f)
        assertEquals(1f, moved.forward.x, 1e-4f)
    }
}
