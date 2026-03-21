package io.github.tetratheta.npviewer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tetratheta.npviewer.update.UpdateChecker

/**
 * "업데이트 가능" 알림 탭 또는 "다운로드 실패" 재시도 알림 탭을 처리하여
 * DownloadManager를 통한 다운로드를 시작하는 리시버.
 */
class UpdateDownloadReceiver : BroadcastReceiver() {
  companion object {
    const val EXTRA_DOWNLOAD_URL = "extra_download_url"
    const val EXTRA_VERSION = "extra_version"
  }

  override fun onReceive(context: Context, intent: Intent) {
    val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return
    val version = intent.getStringExtra(EXTRA_VERSION) ?: return

    // 이미 진행 중인 다운로드가 있으면 중복 요청 방지
    if (UpdateChecker.prefs(context).getLong(UpdateChecker.KEY_DOWNLOAD_ID, -1L) != -1L) return

    // 이전 APK 파일 정리 후 다운로드 시작
    UpdateChecker.cleanupApk(context)
    UpdateChecker.enqueueDownload(context, downloadUrl, version)
  }
}
