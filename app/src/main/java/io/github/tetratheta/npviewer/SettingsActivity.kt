package io.github.tetratheta.npviewer

import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class SettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    if (savedInstanceState == null) {
      supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
    }
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    title = getString(R.string.title_activity_settings)
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  class SettingsFragment : PreferenceFragmentCompat() {
    private var pendingUpdateInfo: UpdateInfo? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      setPreferencesFromResource(R.xml.root_preferences, rootKey)

      findPreference<Preference>("open_link_settings")?.setOnPreferenceClickListener {
        val intent =
          Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:${requireContext().packageName}".toUri())
        startActivity(intent)
        true
      }

      findPreference<Preference>("check_update")?.setOnPreferenceClickListener {
        lifecycleScope.launch { forceCheckUpdate() }
        true
      }

      findPreference<Preference>("download_update")?.setOnPreferenceClickListener {
        val prefs = UpdateChecker.prefs(requireContext())
        val apkPath = prefs.getString(UpdateChecker.KEY_APK_PATH, null)
        if (apkPath != null && File(apkPath).exists()) {
          triggerInstall(apkPath)
        } else {
          triggerDownload()
        }
        true
      }

      findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
        lifecycleScope.launch {
          withContext(Dispatchers.IO) { requireContext().cacheDir.deleteRecursively() }
          Toast.makeText(context, R.string.msg_clear_cache, Toast.LENGTH_SHORT).show()
          updateCacheSummary()
        }
        true
      }

      findPreference<Preference>("clear_webstorage")?.setOnPreferenceClickListener {
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(context, R.string.msg_clear_webstorage, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch { updateWebStorageSummary() }
        true
      }

      findPreference<Preference>("clear_cookie")?.setOnPreferenceClickListener {
        AlertDialog.Builder(requireContext()).setTitle(R.string.title_clear_cookie)
          .setMessage(R.string.msg_clear_cookie_warning).setPositiveButton(R.string.btn_delete) { _, _ ->
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Toast.makeText(context, R.string.msg_clear_cookie, Toast.LENGTH_SHORT).show()
            findPreference<Preference>("clear_cookie")?.summary = "${getString(R.string.pref_desc_clear_cookie)}\n0 B"
          }.setNegativeButton(R.string.btn_cancel, null).show()
        true
      }
    }

    override fun onResume() {
      super.onResume()
      updateLinkSettingsPref()
      lifecycleScope.launch {
        updateStorageSummaries()
        updateUpdatePrefs()
      }
    }

    // region Update

    private suspend fun updateUpdatePrefs() {
      if (!isAdded) return
      val checkPref = findPreference<Preference>("check_update") ?: return
      val downloadPref = findPreference<Preference>("download_update") ?: return

      val prefs = UpdateChecker.prefs(requireContext())
      var apkReady = checkApkReady(prefs)

      if (apkReady) {
        downloadPref.title = getString(R.string.pref_key_install_update)
        downloadPref.summary =
          getString(R.string.pref_desc_apk_downloaded, prefs.getString(UpdateChecker.KEY_APK_VERSION, ""))
        downloadPref.isEnabled = true
      } else {
        downloadPref.title = getString(R.string.pref_key_download_update)
        downloadPref.summary = null
      }

      val isDownloading = prefs.getLong(UpdateChecker.KEY_DOWNLOAD_ID, -1L) != -1L
      if (isDownloading) {
        checkPref.isEnabled = false
        checkPref.summary = getString(R.string.pref_desc_update_downloading)
        if (!apkReady) downloadPref.isEnabled = false
        return
      }

      if (UpdateChecker.hasFreshCache(requireContext())) {
        val cached = UpdateChecker.getCachedInfo(requireContext())
        pendingUpdateInfo = cached
        apkReady = maybeCleanStaleApk(prefs, cached?.version)
        applyUpdateResult(cached, apkReady = apkReady)
        return
      }

      setCheckingState(keepDownloadPref = apkReady)
      val result = UpdateChecker.fetchLatest(requireContext())
      UpdateChecker.saveCache(requireContext(), result)
      if (!isAdded) return

      val newInfo = if (result is UpdateResult.Available) result.info else null
      pendingUpdateInfo = newInfo
      apkReady = maybeCleanStaleApk(prefs, newInfo?.version)
      applyUpdateResult(newInfo, isError = result is UpdateResult.Error, apkReady = apkReady)
    }

    private suspend fun forceCheckUpdate() {
      if (!isAdded) return
      val prefs = UpdateChecker.prefs(requireContext())
      val apkReady = checkApkReady(prefs)
      setCheckingState(keepDownloadPref = apkReady)

      val result = UpdateChecker.fetchLatest(requireContext())
      UpdateChecker.saveCache(requireContext(), result)
      if (!isAdded) return

      val newInfo = if (result is UpdateResult.Available) result.info else null
      pendingUpdateInfo = newInfo
      val updatedApkReady = maybeCleanStaleApk(prefs, newInfo?.version)
      applyUpdateResult(newInfo, isError = result is UpdateResult.Error, apkReady = updatedApkReady)
    }

    private fun setCheckingState(keepDownloadPref: Boolean = false) {
      findPreference<Preference>("check_update")?.apply {
        isEnabled = false
        summary = getString(R.string.pref_desc_check_update_checking)
      }
      if (!keepDownloadPref) findPreference<Preference>("download_update")?.isEnabled = false
    }

    private fun applyUpdateResult(info: UpdateInfo?, isError: Boolean = false, apkReady: Boolean = false) {
      val checkPref = findPreference<Preference>("check_update") ?: return
      val downloadPref = findPreference<Preference>("download_update") ?: return
      checkPref.isEnabled = true
      checkPref.summary = when {
        info != null -> getString(R.string.pref_desc_check_update_available, info.version)
        isError -> getString(R.string.pref_desc_check_update_error)
        else -> getString(R.string.pref_desc_check_update_latest)
      }
      if (!apkReady) {
        downloadPref.title = getString(R.string.pref_key_download_update)
        downloadPref.summary = null
        downloadPref.isEnabled = info != null
      } // If apkReady, download_update already shows "Install Update" — don't override it
    }

    private fun triggerDownload() {
      val info = pendingUpdateInfo ?: return
      val context = requireContext()
      val request = DownloadManager.Request(Uri.parse(info.downloadUrl)).apply {
        setTitle(getString(R.string.noti_download_title))
        setDescription(getString(R.string.noti_download_desc, info.version))
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalFilesDir(context, null, "np-viewer-${info.version}.apk")
      }
      val dm = context.getSystemService(DownloadManager::class.java)
      val downloadId = dm.enqueue(request)
      UpdateChecker.prefs(context).edit { putLong(UpdateChecker.KEY_DOWNLOAD_ID, downloadId) }
      findPreference<Preference>("check_update")?.apply {
        isEnabled = false
        summary = getString(R.string.pref_desc_update_downloading)
      }
      findPreference<Preference>("download_update")?.isEnabled = false
    }

    private fun triggerInstall(apkPath: String) {
      val context = requireContext()
      val apkFile = File(apkPath)
      val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
      val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      UpdateChecker.prefs(context).edit {
        remove(UpdateChecker.KEY_APK_PATH).remove(UpdateChecker.KEY_APK_VERSION)
      }
      findPreference<Preference>("download_update")?.apply {
        title = getString(R.string.pref_key_download_update)
        summary = null
        isEnabled = false
      }
      startActivity(installIntent)
    }

    private fun checkApkReady(prefs: SharedPreferences): Boolean {
      val apkPath = prefs.getString(UpdateChecker.KEY_APK_PATH, null) ?: return false
      val apkVersion = prefs.getString(UpdateChecker.KEY_APK_VERSION, null) ?: return false
      return apkVersion.isNotEmpty() && File(apkPath).exists()
    }

    /** If remoteVersion is newer than the stored APK version, deletes the APK and clears prefs.
     *  Returns true if the APK is still present and valid. */
    private fun maybeCleanStaleApk(prefs: SharedPreferences, remoteVersion: String?): Boolean {
      val apkPath = prefs.getString(UpdateChecker.KEY_APK_PATH, null) ?: return false
      val apkVersion = prefs.getString(UpdateChecker.KEY_APK_VERSION, null) ?: return false
      val apkFile = File(apkPath)
      if (!apkFile.exists()) {
        prefs.edit { remove(UpdateChecker.KEY_APK_PATH).remove(UpdateChecker.KEY_APK_VERSION) }
        return false
      }
      if (remoteVersion != null && UpdateChecker.isNewer(remoteVersion, apkVersion)) {
        apkFile.delete()
        prefs.edit { remove(UpdateChecker.KEY_APK_PATH).remove(UpdateChecker.KEY_APK_VERSION) }
        findPreference<Preference>("download_update")?.apply {
          title = getString(R.string.pref_key_download_update)
          summary = null
        }
        return false
      }
      return true
    }

    // endregion

    private fun updateLinkSettingsPref() {
      val pref = findPreference<Preference>("open_link_settings") ?: return
      val approved = isLinkApproved()
      pref.isEnabled = !approved
      pref.summary = getString(
        if (approved) R.string.pref_desc_open_link_settings_enabled
        else R.string.pref_desc_open_link_settings_disabled
      )
    }

    private suspend fun updateStorageSummaries() {
      coroutineScope {
        launch { updateCacheSummary() }
        launch { updateWebStorageSummary() }
        launch { updateCookieSummary() }
      }
    }

    private suspend fun updateCacheSummary() {
      val cachePref = findPreference<Preference>("clear_cache") ?: return
      val size = withContext(Dispatchers.IO) {
        requireContext().cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
      }
      cachePref.summary = "${getString(R.string.pref_desc_clear_cache)}\n${formatSize(size)}"
    }

    private suspend fun updateWebStorageSummary() {
      val webstoragePref = findPreference<Preference>("clear_webstorage") ?: return
      val size = suspendCancellableCoroutine { cont ->
        WebStorage.getInstance().getOrigins { origins ->
          val total = origins?.values?.filterIsInstance<WebStorage.Origin>()?.sumOf { it.usage } ?: 0L
          cont.resume(total)
        }
      }
      webstoragePref.summary = "${getString(R.string.pref_desc_clear_webstorage)}\n${formatSize(size)}"
    }

    private suspend fun updateCookieSummary() {
      val cookiePref = findPreference<Preference>("clear_cookie") ?: return
      val size = withContext(Dispatchers.IO) {
        val cookieFile = File(requireContext().dataDir, "app_webview/Default/Cookies")
        if (cookieFile.exists()) cookieFile.length() else 0L
      }
      cookiePref.summary = "${getString(R.string.pref_desc_clear_cookie)}\n${formatSize(size)}"
    }

    private fun formatSize(bytes: Long): String = when {
      bytes <= 0L -> "0 B"
      bytes < 1_024L -> "$bytes B"
      bytes < 1_048_576L -> "${bytes / 1_024} KB"
      else -> "%.1f MB".format(bytes / 1_048_576.0)
    }

    private fun isLinkApproved(): Boolean {
      return try {
        val manager = requireContext().getSystemService(DomainVerificationManager::class.java)
        val userState = manager.getDomainVerificationUserState(requireContext().packageName) ?: return false
        val state = userState.hostToStateMap["novelpia.com"] ?: return false
        state == DomainVerificationUserState.DOMAIN_STATE_SELECTED || state == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
      } catch (_: Exception) {
        false
      }
    }
  }
}
