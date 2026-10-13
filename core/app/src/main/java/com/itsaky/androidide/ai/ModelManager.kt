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

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** A model file that is present on the device. */
data class LocalModel(val spec: ModelSpec, val file: File)

/** State of the on-device models needed by the current mode, observable from the UI. */
sealed class ModelState {
  /** The current mode runs no local models. */
  object NotNeeded : ModelState()

  object Missing : ModelState()

  /**
   * @property step 1-based number of the file being fetched, out of [steps]
   * @property copying true when the file is copied out of the APK instead of downloaded
   */
  data class Downloading(
    val name: String,
    val step: Int,
    val steps: Int,
    val downloaded: Long,
    val total: Long,
    val copying: Boolean = false,
  ) : ModelState()

  data class Ready(val models: Map<ModelRole, LocalModel>) : ModelState()

  data class Failed(val message: String) : ModelState()
}

/**
 * Owns the model files: where they live, which are present, and the (user-triggered, resumable,
 * background) installation of the ones the current mode needs. Nothing is downloaded until the
 * user chooses a local mode.
 */
object ModelManager {

  const val UNIQUE_WORK_NAME = "ai-model-install"
  const val KEY_MODEL_IDS = "model_ids"

  private val listeners = CopyOnWriteArrayList<(ModelState) -> Unit>()
  private val main = Handler(Looper.getMainLooper())

  @Volatile
  var state: ModelState = ModelState.NotNeeded
    private set

  fun addListener(listener: (ModelState) -> Unit) {
    listeners.add(listener)
    listener(state)
  }

  fun removeListener(listener: (ModelState) -> Unit) {
    listeners.remove(listener)
  }

  internal fun publish(newState: ModelState) {
    state = newState
    main.post { listeners.forEach { it(newState) } }
  }

  fun modelDir(context: Context): File = File(context.applicationContext.filesDir, "ai-models")

  fun fileFor(context: Context, spec: ModelSpec): File = File(modelDir(context), spec.fileName)

  fun isInstalled(context: Context, spec: ModelSpec): Boolean {
    val f = fileFor(context, spec)
    return f.isFile && f.length() >= spec.minBytes
  }

  /** Models that were packed inside this APK (offline builds). */
  fun bundledSpecs(context: Context): List<ModelSpec> {
    val names = try {
      context.applicationContext.assets.list(ModelSpec.ASSET_DIR)?.toSet().orEmpty()
    } catch (e: Exception) {
      emptySet()
    }
    return ModelCatalog.all.filter { it.fileName in names }
  }

  /** The models [mode] uses on this device, honouring the user's per-role choice. */
  fun specsFor(context: Context, mode: AiMode): List<ModelSpec> =
    ModelCatalog.forMode(mode) { AiSettings.modelChoice(context.applicationContext, it) }

  /** All files [mode] needs, if every one is present; otherwise null. */
  fun readyModels(context: Context, mode: AiMode): Map<ModelRole, LocalModel>? {
    val specs = specsFor(context, mode)
    if (specs.isEmpty() || !specs.all { isInstalled(context, it) }) return null
    return specs.associate { it.role to LocalModel(it, fileFor(context, it)) }
  }

  /** Recomputes [state] from the saved mode and what is on disk (keeps an active download). */
  fun refresh(context: Context) {
    val mode = AiSettings.mode(context)
    if (!mode.isLocal) {
      publish(ModelState.NotNeeded)
      return
    }
    if (state is ModelState.Downloading) return
    val ready = readyModels(context, mode)
    publish(
      when {
        ready != null -> ModelState.Ready(ready)
        state is ModelState.Failed -> state
        else -> ModelState.Missing
      }
    )
  }

  /**
   * Installs the models [mode] needs: copies them out of the APK when bundled, downloads them
   * otherwise. Does nothing for modes that run no local model.
   *
   * @param ignoreNetworkLimit allow mobile data for this download (the user said so explicitly).
   */
  fun install(context: Context, mode: AiMode, ignoreNetworkLimit: Boolean = false) {
    val app = context.applicationContext
    if (!mode.isLocal) {
      publish(ModelState.NotNeeded)
      return
    }

    val missing = specsFor(app, mode).filterNot { isInstalled(app, it) }
    if (missing.isEmpty()) {
      readyModels(app, mode)?.let { publish(ModelState.Ready(it)) }
      return
    }
    enqueue(app, missing, ignoreNetworkLimit)
  }

  /**
   * Downloads exactly [specs] (e.g. one extra model picked in the library), whatever the mode.
   * Models already on the disk are skipped.
   */
  fun installSpecs(context: Context, specs: List<ModelSpec>, ignoreNetworkLimit: Boolean = false) {
    val app = context.applicationContext
    val missing = specs.filterNot { isInstalled(app, it) }
    if (missing.isEmpty()) {
      refresh(app)
      return
    }
    enqueue(app, missing, ignoreNetworkLimit)
  }

  private fun enqueue(app: Context, missing: List<ModelSpec>, ignoreNetworkLimit: Boolean) {

    val bundled = bundledSpecs(app).map { it.id }.toSet()
    val needsNetwork = missing.any { it.id !in bundled }
    val wifiOnly = AiSettings.isWifiOnly(app) && !ignoreNetworkLimit
    val constraints = Constraints.Builder()
      .setRequiredNetworkType(
        when {
          !needsNetwork -> NetworkType.NOT_REQUIRED
          wifiOnly -> NetworkType.UNMETERED
          else -> NetworkType.CONNECTED
        }
      )
      .setRequiresStorageNotLow(true)
      .build()

    val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
      .setConstraints(constraints)
      .setInputData(
        Data.Builder()
          .putStringArray(KEY_MODEL_IDS, missing.map { it.id }.toTypedArray())
          .build()
      )
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
      .build()

    // REPLACE: a new choice (other mode, or "download now on mobile data") supersedes the old job.
    WorkManager.getInstance(app)
      .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    publish(ModelState.Downloading(missing.first().displayName, 1, missing.size, 0, -1))
  }

  /** Stops any installation in progress. */
  fun cancel(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
  }

  /** Deletes every downloaded model and frees the space. */
  fun deleteAll(context: Context) {
    val app = context.applicationContext
    cancel(app)
    modelDir(app).deleteRecursively()
    publish(if (AiSettings.mode(app).isLocal) ModelState.Missing else ModelState.NotNeeded)
  }

  /** Space used by downloaded models. */
  fun usedBytes(context: Context): Long =
    modelDir(context).listFiles()?.sumOf { it.length() } ?: 0L
}
