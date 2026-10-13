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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * Runs a model file with MediaPipe's LLM Inference API, fully on the device.
 *
 * The engine is loaded lazily and must be [release]d when idle: the model occupies
 * several hundred MB of RAM while loaded.
 */
class LlmEngine(context: Context) {

  private val appContext = context.applicationContext
  /**
   * The loaded `LlmInference`. Declared as `Any?` on purpose: kapt writes a Java stub for every
   * member, and javac (JDK 17) cannot read MediaPipe's Java 21 class files. Keeping MediaPipe
   * types out of every member signature (they are used only inside method bodies) avoids that.
   */
  private var llm: Any? = null
  private var loadedPath: String? = null

  companion object {
    private val log = LoggerFactory.getLogger(LlmEngine::class.java)
    private const val TOP_K = 20
    private const val TEMPERATURE = 0.3f

    /** Code edits must be precise, so the fixer model samples almost greedily. */
    private const val FIX_TEMPERATURE = 0.1f
    private const val TOP_P = 0.9f
  }

  @Synchronized
  private fun ensureLoaded(model: LocalModel) {
    val path = model.file.absolutePath
    if (llm != null && loadedPath == path) return
    releaseLocked()

    val options = com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions.builder()
      .setModelPath(path)
      // Must not exceed the context window the model file was converted with.
      .setMaxTokens(model.spec.contextTokens)
      .build()
    llm = com.google.mediapipe.tasks.genai.llminference.LlmInference.createFromOptions(appContext, options)
    loadedPath = path
  }

  /**
   * Generates an answer for [prompt], streaming the text-so-far to [onPartial].
   * Cancelling the calling coroutine cancels the generation.
   *
   * @throws Throwable (including [UnsatisfiedLinkError] on unsupported CPUs) if the
   *   model cannot be loaded; callers must be ready to fall back to the built-in hints.
   */
  suspend fun generate(
    model: LocalModel,
    prompt: String,
    onPartial: (String) -> Unit,
  ): String = withContext(Dispatchers.IO) {
    ensureLoaded(model)
    val engine = checkNotNull(llm) { "Model is not loaded" } as com.google.mediapipe.tasks.genai.llminference.LlmInference

    val sessionOptions = com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.LlmInferenceSessionOptions.builder()
      .setTopK(TOP_K)
      .setTemperature(if (model.spec.role == ModelRole.FIXER) FIX_TEMPERATURE else TEMPERATURE)
      .setTopP(TOP_P)
      .build()
    val session = com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession.createFromOptions(engine, sessionOptions)

    val acc = StringBuilder()
    try {
      session.addQueryChunk(prompt)
      val future = session.generateResponseAsync(com.google.mediapipe.tasks.genai.llminference.ProgressListener<String> { partial, _ ->
        acc.append(partial)
        onPartial(acc.toString())
      })

      // Poll so that coroutine cancellation can stop the native generation promptly.
      while (true) {
        try {
          future.get(250, TimeUnit.MILLISECONDS)
          break
        } catch (e: TimeoutException) {
          if (!isActive) {
            session.cancelGenerateResponseAsync()
            throw CancellationException("Generation cancelled")
          }
        }
      }
      acc.toString().trim()
    } finally {
      try {
        session.close()
      } catch (e: Exception) {
        log.debug("Error while closing the LLM session", e)
      }
    }
  }

  @Synchronized
  fun release() = releaseLocked()

  private fun releaseLocked() {
    try {
      (llm as? com.google.mediapipe.tasks.genai.llminference.LlmInference)?.close()
    } catch (e: Exception) {
      log.debug("Error while closing the LLM engine", e)
    }
    llm = null
    loadedPath = null
  }
}
