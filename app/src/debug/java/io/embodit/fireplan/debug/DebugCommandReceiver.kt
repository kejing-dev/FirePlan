package io.embodit.fireplan.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.embodit.fireplan.platform.FireLog

/**
 * Debug builds only. Forwards adb broadcasts to the running Stage (action `io.embodit.fireplan.DEBUG`);
 * see [DebugCommands] for the command list.
 *
 *   adb -s emulator-5562 shell "am broadcast -a io.embodit.fireplan.DEBUG -p io.embodit.fireplan --es cmd 'state S12'"
 *
 * Extras: `cmd` = command name with its arguments after it, or `a` = comma-separated numbers and `s` = text.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val a = intent.getStringExtra("a")
        val s = intent.getStringExtra("s")
        FireLog.i("CMD recv '$cmd' a='$a' s='$s'")
        DebugCommands.run(context.applicationContext, cmd, a, s)
    }
}
