package io.github.tetratheta.npviewer

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.tetratheta.npviewer.update.UpdateChecker
import java.io.File

class DownloadReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

    val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
    val prefs = UpdateChecker.prefs(context)
    val storedId = prefs.getLong(UpdateChecker.KEY_DOWNLOAD_ID, -1L)
    if (completedId != storedId || storedId == -1L) return

    val dm = context.getSystemService(DownloadManager::class.java)
    val cursor = dm.query(DownloadManager.Query().setFilterById(completedId))
    if (!cursor.moveToFirst()) {
      cursor.close()
      return
    }

    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
    val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
    cursor.close()

    // Always clear the download ID now that the download has completed (success or failure)
    prefs.edit { remove(UpdateChecker.KEY_DOWNLOAD_ID) }

    if (status != DownloadManager.STATUS_SUCCESSFUL || localUri == null) return

    val apkPath = localUri.toUri().path ?: return
    val apkFile = File(apkPath)
    if (!apkFile.exists()) return

    val apkVersion = apkFile.nameWithoutExtension.removePrefix("np-viewer-")
    prefs.edit {
      putString(UpdateChecker.KEY_APK_PATH, apkFile.absolutePath)
        .putString(UpdateChecker.KEY_APK_VERSION, apkVersion)
    }

    val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(apkUri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val pendingIntent = PendingIntent.getActivity(
      context, 0, installIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val channelId = "update_install"
    val nm = context.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(
      NotificationChannel(
        channelId, context.getString(R.string.noti_channel_update), NotificationManager.IMPORTANCE_HIGH
      )
    )

    val notification = NotificationCompat.Builder(context, channelId).setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(context.getString(R.string.noti_update_ready_title))
      .setContentText(context.getString(R.string.noti_update_ready_text)).setContentIntent(pendingIntent)
      .setAutoCancel(true).build()

    nm.notify(NOTIFICATION_ID, notification)
  }

  companion object {
    const val NOTIFICATION_ID = 1001
  }
}
