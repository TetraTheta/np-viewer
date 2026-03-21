package io.github.tetratheta.npviewer.activity

import android.content.Intent
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
    companion object {
      // @RequiresApi(33) 회피를 위해 문자열 상수로 선언
      private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }

    private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!isAdded) return@registerForActivityResult
        if (!granted) {
          if (!shouldShowRequestPermissionRationale(POST_NOTIFICATIONS)) {
            showGoToSettingsDialog()
          } else {
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
      setupVersionPref()
      setupLinkSettingsPref()
      setupUpdatePrefs()
      setupDataPrefs()
    }

    override fun onResume() {
      super.onResume()
      refreshLinkSettingsPref()
      lifecycleScope.launch {
        refreshStorageSummaries()
        refreshUpdatePrefs()
      }
    }

    // region 환경 설정 초기화

    private fun setupVersionPref() {
      findPreference<Preference>("current_version")?.summary =
        requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
    }

    private fun setupLinkSettingsPref() {
      findPreference<Preference>("open_link_settings")?.setOnPreferenceClickListener {
        startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:${requireContext().packageName}".toUri()))
        true
      }
    }

    private fun setupUpdatePrefs() {
      findPreference<Preference>("check_update")?.setOnPreferenceClickListener {
        requestNotificationPermissionThen { lifecycleScope.launch { forceCheckUpdate() } }
        true
      }

      findPreference<Preference>("download_update")?.setOnPreferenceClickListener {
        val apkPath = UpdateChecker.prefs(requireContext()).getString(UpdateChecker.KEY_APK_PATH, null)
        if (apkPath != null && File(apkPath).exists()) {
          triggerInstall(apkPath)
        } else {
          requestNotificationPermissionThen { triggerDownload() }
        }
        true
      }
    }

    private fun setupDataPrefs() {
      findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
        lifecycleScope.launch {
          withContext(Dispatchers.IO) { requireContext().cacheDir.deleteRecursively() }
          Toast.makeText(context, R.string.msg_clear_cache, Toast.LENGTH_SHORT).show()
          refreshCacheSummary()
        }
        true
      }

      findPreference<Preference>("clear_webstorage")?.setOnPreferenceClickListener {
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(context, R.string.msg_clear_webstorage, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch { refreshWebStorageSummary() }
        true
      }

      findPreference<Preference>("clear_cookie")?.setOnPreferenceClickListener {
        AlertDialog.Builder(requireContext())
          .setTitle(R.string.title_clear_cookie)
          .setMessage(R.string.msg_clear_cookie_warning)
          .setPositiveButton(R.string.btn_delete) { _, _ ->
            CookieManager.getInstance().apply { removeAllCookies(null); flush() }
            Toast.makeText(context, R.string.msg_clear_cookie, Toast.LENGTH_SHORT).show()
            findPreference<Preference>("clear_cookie")?.summary =
              "${getString(R.string.pref_desc_clear_cookie)}\n0 B"
          }
          .setNegativeButton(R.string.btn_cancel, null)
          .show()
        true
      }
    }

    // endregion

    // region 업데이트

    private fun refreshUpdatePrefs() {
      if (!isAdded) return
      val checkPref = findPreference<Preference>("check_update") ?: return
      val downloadPref = findPreference<Preference>("download_update") ?: return
      val context = requireContext()

      // 이미 설치된 버전의 APK 캐시 제거
      UpdateChecker.purgeInstalledApk(context)

      // 다운로드 진행 중이면 UI 비활성화
      if (UpdateChecker.isDownloadActive(context)) {
        checkPref.isEnabled = false
        checkPref.summary = getString(R.string.pref_desc_update_downloading)
        downloadPref.isEnabled = false
        return
      }

      // APK가 다운로드되어 설치 대기 중인 경우
      if (UpdateChecker.isApkReady(context)) {
        val readyVersion = UpdateChecker.prefs(context).getString(UpdateChecker.KEY_APK_VERSION, "")
        checkPref.isEnabled = true
        checkPref.summary = getString(R.string.pref_desc_check_update_available, readyVersion)
        downloadPref.title = getString(R.string.pref_key_install_update)
        downloadPref.summary = getString(R.string.pref_desc_apk_downloaded, readyVersion)
        downloadPref.isEnabled = true
        return
      }

      // 캐시된 업데이트 정보 확인
      if (UpdateChecker.hasFreshCache(context)) {
        val cached = UpdateChecker.getCachedInfo(context)
        pendingUpdateInfo = cached
        applyUpdateResult(cached)
      } else {
        checkPref.isEnabled = true
        checkPref.summary = getString(R.string.pref_desc_check_update_default)
        downloadPref.isEnabled = false
        downloadPref.title = getString(R.string.pref_key_download_update)
        downloadPref.summary = null
      }
    }

    private suspend fun forceCheckUpdate() {
      if (!isAdded) return
      val context = requireContext()

      // 기존 다운로드 및 APK 정리
      UpdateChecker.cancelDownload(context)
      UpdateChecker.cleanupApk(context)

      setCheckingState()

      val result = UpdateChecker.fetchLatest(context)
      UpdateChecker.saveCache(context, result)
      if (!isAdded) return

      val info = (result as? UpdateResult.Available)?.info
      pendingUpdateInfo = info
      applyUpdateResult(info, isError = result is UpdateResult.Error)
    }

    private fun setCheckingState() {
      findPreference<Preference>("check_update")?.apply {
        isEnabled = false
        summary = getString(R.string.pref_desc_check_update_checking)
      }
      findPreference<Preference>("download_update")?.isEnabled = false
    }

    private fun applyUpdateResult(info: UpdateInfo?, isError: Boolean = false) {
      val checkPref = findPreference<Preference>("check_update") ?: return
      val downloadPref = findPreference<Preference>("download_update") ?: return
      checkPref.isEnabled = true
      checkPref.summary = when {
        info != null -> getString(R.string.pref_desc_check_update_available, info.version)
        isError -> getString(R.string.pref_desc_check_update_error)
        else -> getString(R.string.pref_desc_check_update_latest)
      }
      downloadPref.title = getString(R.string.pref_key_download_update)
      downloadPref.summary = null
      downloadPref.isEnabled = info != null
    }

    private fun triggerDownload() {
      val info = pendingUpdateInfo ?: return
      val context = requireContext()

      val downloadId = UpdateChecker.enqueueDownload(context, info.downloadUrl, info.version)
      if (downloadId == -1L) {
        Toast.makeText(context, "Download failed to start", Toast.LENGTH_LONG).show()
        return
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
      val installIntent = UpdateChecker.createInstallIntent(context, apkFile)

      // 프레퍼런스만 정리 — APK 파일은 설치 프로세스에서 사용하므로 유지
      UpdateChecker.prefs(context).edit {
        remove(UpdateChecker.KEY_APK_PATH)
        remove(UpdateChecker.KEY_APK_VERSION)
      }

      findPreference<Preference>("download_update")?.apply {
        title = getString(R.string.pref_key_download_update)
        summary = null
        isEnabled = false
      }
      startActivity(installIntent)
    }

    // endregion

    // region 알림 권한 (API 33+)

    /** POST_NOTIFICATIONS 권한을 확인하고 필요 시 요청한 후 [action] 실행 */
    private fun requestNotificationPermissionThen(action: () -> Unit) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(requireContext(), POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
      ) {
        action()
        return
      }
      pendingPermissionAction = action
      showNotificationRationaleDialog()
    }

    private fun showNotificationRationaleDialog() {
      AlertDialog.Builder(requireContext())
        .setTitle(R.string.dlg_notification_permission_title)
        .setMessage(R.string.dlg_notification_permission_message)
        .setPositiveButton(R.string.btn_allow) { _, _ ->
          notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
        }
        .setNegativeButton(R.string.btn_skip) { _, _ ->
          pendingPermissionAction?.invoke()
          pendingPermissionAction = null
        }
        .show()
    }

    private fun showGoToSettingsDialog() {
      AlertDialog.Builder(requireContext())
        .setTitle(R.string.dlg_notification_settings_title)
        .setMessage(R.string.dlg_notification_settings_message)
        .setPositiveButton(R.string.btn_go_to_settings) { _, _ ->
          startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", requireContext().packageName, null)
          })
        }
        .setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    // endregion

    // region 링크 설정

    private fun refreshLinkSettingsPref() {
      val pref = findPreference<Preference>("open_link_settings") ?: return
      val approved = isLinkApproved()
      pref.isEnabled = !approved
      pref.summary = getString(
        if (approved) R.string.pref_desc_open_link_settings_enabled
        else R.string.pref_desc_open_link_settings_disabled
      )
    }

    private fun isLinkApproved(): Boolean = try {
      val manager = requireContext().getSystemService(DomainVerificationManager::class.java)
      val state = manager.getDomainVerificationUserState(requireContext().packageName)?.hostToStateMap?.get("novelpia.com")
      state == DomainVerificationUserState.DOMAIN_STATE_SELECTED || state == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
    } catch (_: Exception) {
      false
    }

    // endregion

    // region 저장소 요약

    private suspend fun refreshStorageSummaries() {
      coroutineScope {
        launch { refreshCacheSummary() }
        launch { refreshWebStorageSummary() }
        launch { refreshCookieSummary() }
      }
    }

    private suspend fun refreshCacheSummary() {
      val size = withContext(Dispatchers.IO) {
        requireContext().cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
      }
      findPreference<Preference>("clear_cache")?.summary =
        "${getString(R.string.pref_desc_clear_cache)}\n${formatSize(size)}"
    }

    private suspend fun refreshWebStorageSummary() {
      val size = suspendCancellableCoroutine { cont ->
        WebStorage.getInstance().getOrigins { origins ->
          cont.resume(origins?.values?.filterIsInstance<WebStorage.Origin>()?.sumOf { it.usage } ?: 0L)
        }
      }
      findPreference<Preference>("clear_webstorage")?.summary =
        "${getString(R.string.pref_desc_clear_webstorage)}\n${formatSize(size)}"
    }

    private suspend fun refreshCookieSummary() {
      val size = withContext(Dispatchers.IO) {
        val cookieFile = File(requireContext().dataDir, "app_webview/Default/Cookies")
        if (cookieFile.exists()) cookieFile.length() else 0L
      }
      findPreference<Preference>("clear_cookie")?.summary =
        "${getString(R.string.pref_desc_clear_cookie)}\n${formatSize(size)}"
    }

    private fun formatSize(bytes: Long): String = when {
      bytes <= 0L -> "0 B"
      bytes < 1_024L -> "$bytes B"
      bytes < 1_048_576L -> "${bytes / 1_024} KB"
      else -> "%.1f MB".format(bytes / 1_048_576.0)
    }

    // endregion
  }
}
