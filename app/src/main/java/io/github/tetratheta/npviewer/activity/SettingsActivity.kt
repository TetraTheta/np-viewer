package io.github.tetratheta.npviewer.activity

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
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
import io.github.tetratheta.npviewer.R
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

    private fun createDownloadChannel() {
      val name = getString(R.string.noti_channel_update)
      val descriptionText = getString(R.string.noti_channel_update_desc)
      val importance = NotificationManager.IMPORTANCE_LOW
      val channel = NotificationChannel("download_channel", name, importance).apply {
        description = descriptionText
      }
      val nm = requireContext().getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(channel)
    }

    private fun updateUpdatePrefs() {
      if (!isAdded) return
      val checkPref = findPreference<Preference>("check_update") ?: return
      val downloadPref = findPreference<Preference>("download_update") ?: return
      val context = requireContext()
      val prefs = UpdateChecker.prefs(context)

      // cleanup old APKs if already installed or outdated
      purgeInstalledApkCache(prefs)

      // check current DownloadManager status (handles 'App killed' scenario)
      val downloadId = prefs.getLong(UpdateChecker.KEY_DOWNLOAD_ID, -1L)
      if (downloadId != -1L) {
        val dm = context.getSystemService(DownloadManager::class.java)
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        if (cursor != null && cursor.moveToFirst()) {
          val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
          cursor.close()

          if (status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING) {
            // app was killed, but download is still going. lock the UI.
            checkPref.isEnabled = false
            checkPref.summary = getString(R.string.pref_desc_update_downloading)
            downloadPref.isEnabled = false
            return
          } else if (status == DownloadManager.STATUS_FAILED) {
            // download failed while app was dead. clear the ID so user can retry
            prefs.edit { remove(UpdateChecker.KEY_DOWNLOAD_ID) }
          }
        } else {
          // ID exists in prefs but not in system (cleared by user or system). clean up.
          prefs.edit { remove(UpdateChecker.KEY_DOWNLOAD_ID) }
        }

        // check if APK is downloaded and ready to install
        val apkReady = checkApkReady(prefs)
        if (apkReady) {
          val readyVersion = prefs.getString(UpdateChecker.KEY_APK_VERSION, "")

          checkPref.isEnabled = true
          checkPref.summary = getString(R.string.pref_desc_check_update_available, readyVersion)

          downloadPref.title = getString(R.string.pref_key_install_update)
          downloadPref.summary = getString(R.string.pref_desc_apk_downloaded, readyVersion)
          downloadPref.isEnabled = true
          return
        }

        // check GitHub cache (handles 'Update found but not yet downloaded')
        if (UpdateChecker.hasFreshCache(context)) {
          val cached = UpdateChecker.getCachedInfo(context)
          pendingUpdateInfo = cached
          applyUpdateResult(cached, apkReady = false)
        } else {
          checkPref.isEnabled = true
          checkPref.summary = getString(R.string.pref_desc_check_update_default)

          downloadPref.isEnabled = false
          downloadPref.title = getString(R.string.pref_key_download_update)
          downloadPref.summary = null
        }
      }
    }

    private suspend fun forceCheckUpdate() {
      if (!isAdded) return
      val context = requireContext()
      val prefs = UpdateChecker.prefs(context)

      // purge any existing downloaded APKs when manually checking
      val oldApkPath = prefs.getString(UpdateChecker.KEY_APK_PATH, null)
      if (oldApkPath != null) File(oldApkPath).delete()
      prefs.edit {
        remove(UpdateChecker.KEY_APK_PATH)
        remove(UpdateChecker.KEY_APK_VERSION)

        // cancel any ongoing download in the system
        val currentId = prefs.getLong(UpdateChecker.KEY_DOWNLOAD_ID, -1L)
        if (currentId != -1L) {
          val dm = context.getSystemService(DownloadManager::class.java)
          dm.remove(currentId)
          remove(UpdateChecker.KEY_DOWNLOAD_ID)
        }
      }

      setCheckingState()

      val result = UpdateChecker.fetchLatest(context)
      UpdateChecker.saveCache(context, result)
      if (!isAdded) return

      val newInfo = if (result is UpdateResult.Available) result.info else null
      pendingUpdateInfo = newInfo
      applyUpdateResult(newInfo, isError = result is UpdateResult.Error, apkReady = false)
    }

    private fun setCheckingState() {
      findPreference<Preference>("check_update")?.apply {
        isEnabled = false
        summary = getString(R.string.pref_desc_check_update_checking)
      }
      findPreference<Preference>("download_update")?.isEnabled = false
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

      createDownloadChannel()

      val request = DownloadManager.Request(info.downloadUrl.toUri()).apply {
        setTitle(getString(R.string.noti_download_title))
        setDescription(getString(R.string.noti_download_desc, info.version))

        // use VISIBILITY_VISIBLE so the SYSTEM shows progress but DOES NOT show its own 'Complete' notification (prevents duplicates)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)

        // don't hide the noficiation, SYSTEM!
        setAllowedOverMetered(true)
        setAllowedOverRoaming(true)

        setDestinationInExternalFilesDir(context, null, "np-viewer-${info.version}.apk")
      }
      val dm = context.getSystemService(DownloadManager::class.java)

      try {
        val downloadId = dm.enqueue(request)
        UpdateChecker.prefs(context).edit { putLong(UpdateChecker.KEY_DOWNLOAD_ID, downloadId) }
      } catch (e: Exception) {
        Toast.makeText(context, "Download failed to start: ${e.message}", Toast.LENGTH_LONG).show()
      }

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
      AlertDialog.Builder(requireContext()).setTitle(R.string.diag_notification_permission_title)
        .setMessage(R.string.diag_notification_permission_message).setPositiveButton(R.string.btn_allow) { _, _ ->
          notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
        }.setNegativeButton(R.string.btn_skip) { _, _ ->
          pendingPermissionAction?.invoke()
          pendingPermissionAction = null
        }.show()
    }

    private fun showGoToSettingsDialog() {
      AlertDialog.Builder(requireContext()).setTitle(R.string.diag_notification_settings_title)
        .setMessage(R.string.diag_notification_settings_message)
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
      // declared as a string constant to avoid @RequiresApi(33) on every call site
      private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
  }
}
