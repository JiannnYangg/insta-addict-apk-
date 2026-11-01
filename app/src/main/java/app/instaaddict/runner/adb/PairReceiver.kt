package app.instaaddict.runner.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Receives the 6-digit code typed into the pairing notification. */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = results.getCharSequence(PairingCenter.KEY_CODE)?.toString().orEmpty()
        val pending = goAsync()
        PairingCenter.submit(context.applicationContext, code, null) {
            try {
                pending.finish()
            } catch (_: Exception) {
            }
        }
    }
}
