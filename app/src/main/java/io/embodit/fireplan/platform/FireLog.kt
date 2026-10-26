package io.embodit.fireplan.platform

import android.util.Log
import io.embodit.fireplan.domain.model.Quat4
import io.embodit.fireplan.domain.model.Vec3
import java.util.Locale

/**
 * One line per event under the tag `FirePlan`, with stable prefixes QA greps for:
 * `STATE`, `PLANES`, `TAP`, `MARKER`, `ANCHOR`, `ROUTE`, `DRILL`, `CMD`, ...
 * Positions are Stage metres with four decimals.
 */
object FireLog {
    const val TAG = "FirePlan"

    fun i(line: String) {
        Log.i(TAG, line)
    }

    fun w(line: String, error: Throwable? = null) {
        Log.w(TAG, line, error)
    }

    fun e(line: String, error: Throwable? = null) {
        Log.e(TAG, line, error)
    }

    fun pos(v: Vec3?): String =
        if (v == null) "null" else String.format(Locale.US, "(%.4f, %.4f, %.4f)", v.x, v.y, v.z)

    fun pos5(v: Vec3?): String =
        if (v == null) "null" else String.format(Locale.US, "(%.5f, %.5f, %.5f)", v.x, v.y, v.z)

    fun quat5(q: Quat4?): String =
        if (q == null) "null" else String.format(Locale.US, "q(%.5f, %.5f, %.5f, %.5f)", q.x, q.y, q.z, q.w)

    fun f(v: Float, decimals: Int = 2): String = String.format(Locale.US, "%.${decimals}f", v)
}
