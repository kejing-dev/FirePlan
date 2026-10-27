package io.embodit.fireplan.data

import android.content.Context
import io.embodit.fireplan.domain.model.DrillRecord
import io.embodit.fireplan.domain.model.LanguagePref
import io.embodit.fireplan.domain.model.Marker
import io.embodit.fireplan.domain.model.MarkerKind
import io.embodit.fireplan.domain.model.RoomBox
import io.embodit.fireplan.domain.model.Scenario
import io.embodit.fireplan.domain.model.ScenarioPref
import io.embodit.fireplan.domain.model.Settings
import io.embodit.fireplan.domain.model.StoredRoute
import io.embodit.fireplan.domain.model.StoredSession
import io.embodit.fireplan.domain.model.Vec3
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app remembers between launches (design `persistence`): which anchors are markers (kind,
 * offset, placement facts), the routes in their exit anchor's frame, the last five drills and the
 * settings. Positions are not stored: they come from the anchors.
 */
interface FireRepository {
    /** Null when nothing was stored yet or the stored data cannot be read. */
    suspend fun load(): StoredSession?

    suspend fun save(session: StoredSession)
}

/** SharedPreferences-backed store; one JSON document, written on the IO dispatcher. */
class PrefsFireRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FireRepository {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override suspend fun load(): StoredSession? =
        withContext(io) {
            val text = prefs.getString(KEY, null) ?: return@withContext null
            runCatching { SessionJsonCodec.decode(text) }.getOrNull()
        }

    override suspend fun save(session: StoredSession) {
        withContext(io) { prefs.edit().putString(KEY, SessionJsonCodec.encode(session)).commit() }
    }

    private companion object {
        const val FILE = "fire_plan"
        const val KEY = "session_v1"
    }
}

/** JSON encoding of [StoredSession] (org.json: on the platform, no dependency). */
object SessionJsonCodec {
    fun encode(s: StoredSession): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put(
            "markers",
            JSONArray().apply {
                for (m in s.markers) {
                    put(
                        JSONObject()
                            .put("id", m.id)
                            .put("kind", m.kind.tag)
                            .put("anchor", m.anchorId ?: JSONObject.NULL)
                            .put("offset", vec(m.offset))
                            .put("onTable", m.onTable)
                            .put("onCeiling", m.onCeiling)
                            .put("belowCeiling", m.belowCeilingM ?: JSONObject.NULL)
                            .put("wallDist", m.wallDistM ?: JSONObject.NULL)
                            .put("pos", vec(m.position))
                    )
                }
            },
        )
        root.put(
            "routes",
            JSONArray().apply {
                for (r in s.routes) put(JSONObject().put("exit", r.exitId).put("points", JSONArray().apply { r.pointsInAnchor.forEach { put(vec(it)) } }))
            },
        )
        root.put(
            "records",
            JSONArray().apply {
                for (r in s.records) put(JSONObject().put("at", r.atMs).put("scenario", r.scenario.tag).put("total", r.totalS.toDouble()))
            },
        )
        root.put(
            "settings",
            JSONObject()
                .put("language", s.settings.language.tag)
                .put("hints", s.settings.hints)
                .put("scenario", s.settings.scenario.tag)
                .put("coachSeen", s.settings.coachSeen)
                .put("roomW", s.settings.roomW.toDouble())
                .put("roomL", s.settings.roomL.toDouble())
                .put("roomH", s.settings.roomH.toDouble()),
        )
        return root.toString()
    }

    fun decode(text: String): StoredSession {
        val root = JSONObject(text)
        val markers = ArrayList<Marker>()
        val ms = root.optJSONArray("markers") ?: JSONArray()
        for (i in 0 until ms.length()) {
            val o = ms.getJSONObject(i)
            val kind = MarkerKind.fromTag(o.optString("kind")) ?: continue
            markers +=
                Marker(
                    id = o.getString("id"),
                    kind = kind,
                    position = vec(o.optJSONArray("pos")) ?: Vec3.ZERO,
                    anchorId = if (o.isNull("anchor")) null else o.optString("anchor"),
                    offset = vec(o.optJSONArray("offset")) ?: Vec3.ZERO,
                    onTable = o.optBoolean("onTable", false),
                    onCeiling = o.optBoolean("onCeiling", true),
                    belowCeilingM = if (o.isNull("belowCeiling")) null else o.optDouble("belowCeiling").toFloat(),
                    wallDistM = if (o.isNull("wallDist")) null else o.optDouble("wallDist").toFloat(),
                    saved = !o.isNull("anchor"),
                    found = false,
                )
        }
        val routes = ArrayList<StoredRoute>()
        val rs = root.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until rs.length()) {
            val o = rs.getJSONObject(i)
            val pts = ArrayList<Vec3>()
            val arr = o.optJSONArray("points") ?: JSONArray()
            for (j in 0 until arr.length()) vec(arr.optJSONArray(j))?.let { pts += it }
            routes += StoredRoute(o.getString("exit"), pts)
        }
        val records = ArrayList<DrillRecord>()
        val rec = root.optJSONArray("records") ?: JSONArray()
        for (i in 0 until rec.length()) {
            val o = rec.getJSONObject(i)
            records += DrillRecord(o.optLong("at"), Scenario.fromTag(o.optString("scenario")), o.optDouble("total").toFloat())
        }
        val st = root.optJSONObject("settings") ?: JSONObject()
        val settings =
            Settings(
                language = LanguagePref.fromTag(st.optString("language", "system")),
                hints = st.optBoolean("hints", true),
                scenario = ScenarioPref.entries.firstOrNull { it.tag == st.optString("scenario") } ?: ScenarioPref.RANDOM,
                coachSeen = st.optBoolean("coachSeen", false),
                roomW = st.optDouble("roomW", RoomBox.DEFAULT_W.toDouble()).toFloat().coerceIn(RoomBox.MIN_W, RoomBox.MAX_W),
                roomL = st.optDouble("roomL", RoomBox.DEFAULT_L.toDouble()).toFloat().coerceIn(RoomBox.MIN_L, RoomBox.MAX_L),
                roomH = st.optDouble("roomH", RoomBox.DEFAULT_H.toDouble()).toFloat().coerceIn(RoomBox.MIN_H, RoomBox.MAX_H),
            )
        return StoredSession(markers, routes, records, settings)
    }

    private fun vec(v: Vec3): JSONArray = JSONArray().put(v.x.toDouble()).put(v.y.toDouble()).put(v.z.toDouble())

    private fun vec(a: JSONArray?): Vec3? {
        if (a == null || a.length() < 3) return null
        return Vec3(a.optDouble(0).toFloat(), a.optDouble(1).toFloat(), a.optDouble(2).toFloat())
    }
}
