/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Installs the model files of the chosen mode in the background, with a progress notification.
 * Each file is either copied out of the APK (offline builds) or downloaded (resumable).
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  companion object {
    private val log = LoggerFactory.getLogger(ModelDownloadWorker::class.java)
    private const val CHANNEL_ID = "ai_model_download"
    private const val NOTIFICATION_ID = 7421
    private const val MAX_ATTEMPTS = 10
  }

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val ids = inputData.getStringArray(ModelManager.KEY_MODEL_IDS).orEmpty()
    val specs = ids.mapNotNull { ModelCatalog.byId(it) }
    if (specs.isEmpty()) return@withContext Result.failure()

    try {
      setForeground(foregroundInfo(specs.first().displayName, 1, specs.size, 0, -1))
    } catch (e: Exception) {
      // Foreground promotion can be refused (e.g. app in background on newer Android).
      // The work still runs as ordinary background work.
      log.warn("Unable to run the model installation in the foreground", e)
    }

    val bundled = ModelManager.bundledSpecs(applicationContext).map { it.id }.toSet()
    val dir = ModelManager.modelDir(applicationContext)

    try {
      specs.forEachIndexed { i, spec ->
        val step = i + 1
        if (ModelManager.isInstalled(applicationContext, spec)) return@forEachIndexed
        val onProgress = { done: Long, total: Long ->
          ModelManager.publish(
            ModelState.Downloading(spec.displayName, step, specs.size, done, total, spec.id in bundled)
          )
          notifyProgress(spec.displayName, step, specs.size, done, total)
        }
        if (spec.id in bundled) {
          copyFromAssets(spec, dir, onProgress)
        } else {
          ModelDownloader().download(
            spec = spec,
            dir = dir,
            progress = ModelDownloader.Progress { done, total -> onProgress(done, total) },
            isCancelled = { isStopped },
          )
        }
      }
      ModelManager.refresh(applicationContext)
      Result.success()
    } catch (e: ModelDownloader.CancelledException) {
      // Stopped by the system (constraints lost etc.); WorkManager will run us again.
      Result.retry()
    } catch (e: IOException) {
      log.warn("Model installation failed (attempt {})", runAttemptCount + 1, e)
      if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
        ModelManager.publish(ModelState.Failed(e.message ?: "Installation failed"))
        Result.failure()
      } else {
        Result.retry()
      }
    }
  }

  private fun copyFromAssets(spec: ModelSpec, dir: File, onProgress: (Long, Long) -> Unit) {
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
    val part = File(dir, spec.fileName + ".part")
    val total = try {
      applicationContext.assets.openFd(spec.assetPath).use { it.length }
    } catch (e: IOException) {
      -1L // compressed asset: the size is unknown
    }

    var copied = 0L
    var last = 0L
    applicationContext.assets.open(spec.assetPath).use { input ->
      part.outputStream().use { out ->
        val buf = ByteArray(256 * 1024)
        while (true) {
          if (isStopped) throw ModelDownloader.CancelledException()
          val n = input.read(buf)
          if (n < 0) break
          out.write(buf, 0, n)
          copied += n
          val now = System.currentTimeMillis()
          if (now - last >= 500) {
            last = now
            onProgress(copied, total)
          }
        }
      }
    }
    if (part.length() < spec.minBytes) {
      part.delete()
      throw IOException("Bundled model ${spec.fileName} is too small")
    }
    val finalFile = File(dir, spec.fileName)
    if (finalFile.exists()) finalFile.delete()
    if (!part.renameTo(finalFile)) throw IOException("Cannot move ${part.name}")
  }

  private fun ensureChannel(): NotificationManager {
    val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
      NotificationChannel(CHANNEL_ID, "AI model installation", NotificationManager.IMPORTANCE_LOW)
    )
    return nm
  }

  private fun buildNotification(name: String, step: Int, steps: Int, done: Long, total: Long) =
    NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle("AI helper: $name ($step/$steps)")
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .apply {
        if (total > 0) {
          setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
          setContentText("${done / (1024 * 1024)} / ${total / (1024 * 1024)} MB")
        } else {
          setProgress(0, 0, true)
        }
      }
      .build()

  private fun foregroundInfo(name: String, step: Int, steps: Int, done: Long, total: Long): ForegroundInfo {
    ensureChannel()
    return ForegroundInfo(NOTIFICATION_ID, buildNotification(name, step, steps, done, total))
  }

  private fun notifyProgress(name: String, step: Int, steps: Int, done: Long, total: Long) {
    try {
      ensureChannel().notify(NOTIFICATION_ID, buildNotification(name, step, steps, done, total))
    } catch (e: Exception) {
      // Notifications are cosmetic.
    }
  }
}
