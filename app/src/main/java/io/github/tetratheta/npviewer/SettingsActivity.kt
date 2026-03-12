package io.github.tetratheta.npviewer

import android.app.DownloadManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.tetratheta.npviewer.update.UpdateChecker
import io.github.tetratheta.npviewer.update.UpdateInfo
import io.github.tetratheta.npviewer.update.UpdateResult
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
    private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isAdded) return@registerForActivityResult
        if (!isGranted) {
          if (!shouldShowRequestPermissionRationale(POST_NOTIFICATIONS)) { // "Don't ask again" was selected — system won't ask again
            showGoToSettingsDialog()
          } else { // Declined this time but can ask again later
            Toast.makeText(requireContext(), R.string.msg_notification_permission_denied, Toast.LENGTH_LONG).show()
          }
        }
        pendingPermissionAction?.invoke()
        pendingPermissionAction = null
      }

    private var pendingUpdateInfo: UpdateInfo? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      setPreferencesFromResource(R.xml.root_preferences, rootKey)

      findPreference<Preference>("current_version")?.summary =
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName

      findPreference<Preference>("open_link_settings")?.setOnPreferenceClickListener {
        val intent =
          Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:${requireContext().packageName}".toUri())
        startActivity(intent)
        true
      }

      findPreference<Preference>("check_update")?.setOnPreferenceClickListener {
        requestNotificationPermissionThen { lifecycleScope.launch { forceCheckUpdate() } }
        true
      }

      findPreference<Preference>("download_update")?.setOnPreferenceClickListener {
        val prefs = UpdateChecker.prefs(requireContext())
        val apkPath = prefs.getString(UpdateChecker.KEY_APK_PATH, null)
        if (apkPath != null && File(apkPath).exists()) {
          triggerInstall(apkPath)
        } else {
          requestNotificationPermissionThen { triggerDownload() }
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

      // Purge APK cache if the currently installed version is already >= the stored APK version
      purgeInstalledApkCache(prefs)

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

      // No fresh cache and no auto-fetch: show default idle state
      checkPref.isEnabled = true
      checkPref.summary = getString(R.string.pref_desc_check_update_default)
      if (!apkReady) {
        downloadPref.title = getString(R.string.pref_key_download_update)
        downloadPref.summary = null
        downloadPref.isEnabled = false
      }
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
      val request = DownloadManager.Request(info.downloadUrl.toUri()).apply {
        setTitle(getString(R.string.noti_download_title))
        setDescription(getString(R.string.noti_download_desc, info.version))
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
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
      apkFile.delete()
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

    /** Deletes cached APK and clears prefs if the stored APK version is not newer than the
     *  currently installed version (i.e., the user already installed it or it's outdated). */
    private fun purgeInstalledApkCache(prefs: SharedPreferences) {
      val apkVersion = prefs.getString(UpdateChecker.KEY_APK_VERSION, null) ?: return
      val currentVersion = try {
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: return
      } catch (_: Exception) {
        return
      }
      if (!UpdateChecker.isNewer(apkVersion, currentVersion)) {
        val apkPath = prefs.getString(UpdateChecker.KEY_APK_PATH, null)
        if (apkPath != null) File(apkPath).delete()
        prefs.edit { remove(UpdateChecker.KEY_APK_PATH).remove(UpdateChecker.KEY_APK_VERSION) }
      }
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

    // region Notification Permission (API 33+)

    /** Requests POST_NOTIFICATIONS permission if needed, then runs [action].
     *  On API < 33 or if already granted, runs [action] immediately. */
    private fun requestNotificationPermissionThen(action: () -> Unit) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
          requireContext(), POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
      ) {
        action()
        return
      }
      pendingPermissionAction = action
      showNotificationRationaleDialog()
    }

    private fun showNotificationRationaleDialog() {
      AlertDialog.Builder(requireContext()).setTitle(R.string.dialog_notification_permission_title)
        .setMessage(R.string.dialog_notification_permission_message).setPositiveButton(R.string.btn_allow) { _, _ ->
          notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
        }.setNegativeButton(R.string.btn_skip) { _, _ ->
          pendingPermissionAction?.invoke()
          pendingPermissionAction = null
        }.show()
    }

    private fun showGoToSettingsDialog() {
      AlertDialog.Builder(requireContext()).setTitle(R.string.dialog_notification_settings_title)
        .setMessage(R.string.dialog_notification_settings_message)
        .setPositiveButton(R.string.btn_go_to_settings) { _, _ ->
          val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
          }
          startActivity(intent)
        }.setNegativeButton(R.string.btn_cancel, null).show()
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

    companion object {
      // Declared as a string constant to avoid @RequiresApi(33) on every call site
      private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
  }
}
