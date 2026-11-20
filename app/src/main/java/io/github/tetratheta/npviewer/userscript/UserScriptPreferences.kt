package io.github.tetratheta.npviewer.userscript

import android.content.Context
import androidx.preference.PreferenceManager

object UserScriptPreferences {
  const val KEY_ENABLED = "userscripts_enabled"
  const val KEY_LAST_UPDATED_AT = "userscripts_last_updated_at"
  const val KEY_LAST_UPDATE_SUMMARY = "userscripts_last_update_summary"
  private const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

  fun isEnabled(context: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, true)

  fun shouldUpdate(
    context: Context,
    now: Long = System.currentTimeMillis(),
  ): Boolean = now - PreferenceManager.getDefaultSharedPreferences(context).getLong(KEY_LAST_UPDATED_AT, 0L) >= UPDATE_INTERVAL_MS
}
