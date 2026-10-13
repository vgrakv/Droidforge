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
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

enum class ItemStatus {
  /** Only the built-in hint is available (no AI chosen, or online mode without a token). */
  HINT_ONLY,

  /** Waiting for the model download to finish. */
  WAITING_MODEL,

  QUEUED,
  RUNNING,

  /** The English answer is ready and is being translated to Hebrew. */
  TRANSLATING,
  DONE,
  FAILED,
}

/** State of the automatic code fix for one error. */
sealed class FixState {
  object None : FixState()
  object Generating : FixState()

  /** A validated fix, ready to be applied after the user confirms. */
  data class Proposed(val blocks: List<EditBlock>, val preview: String) : FixState()

  data class NoFix(val reason: String) : FixState()
  data class Failed(val message: String) : FixState()

  /** Applied to [file]; [original] is kept so the change can be undone. */
  data class Applied(val file: File, val original: String, val blocks: List<EditBlock>) : FixState()
}

/**
 * One error shown in the "Explain errors" tab.
 *
 * @property hint instant, hand-written explanation (may be null for unknown errors)
 * @property ai text produced by a model (grows while [status] is RUNNING)
 * @property canFix the current mode can propose a code fix for this error
 */
data class ExplainItem(
  val error: BuildError,
  val hint: String?,
  val ai: String = "",
  val status: ItemStatus,
  val failure: String? = null,
  val canFix: Boolean = false,
  val fix: FixState = FixState.None,
)

/**
 * Glue between the build and the AI helper:
 *
 *  1. collects the build output (local modes never send anything over the network),
 *  2. when the build fails, parses it into structured errors,
 *  3. shows an instant hint from [ErrorKnowledgeBase],
 *  4. explains each error with the model(s) of the chosen mode,
 *  5. on request, proposes a code fix (heavy and online modes).
 */
object ErrorExplainer {

  private const val MAX_LOG_CHARS = 300_000
  private const val MAX_ERRORS_TO_EXPLAIN = 3
  private const val LOG_SETTLE_MS = 600L
  private const val ONLINE_EXPLAIN_TOKENS = 700
  private const val ONLINE_FIX_TOKENS = 2000
  private const val FIX_PROMPT_CHARS_LOCAL = PromptBuilder.DEFAULT_MAX_FIX_PROMPT_CHARS
  private const val HEAVY_EXPLAIN_PROMPT_CHARS = 5000
  private val log = LoggerFactory.getLogger(ErrorExplainer::class.java)

  private val lock = Any()
  private val logBuffer = StringBuilder()
  private val listeners = CopyOnWriteArrayList<(List<ExplainItem>) -> Unit>()
  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Only one model may be loaded at a time: they take gigabytes of RAM. */
  private val modelLock = Mutex()

  private var items: List<ExplainItem> = emptyList()
  private var job: Job? = null
  private var modelListener: ((ModelState) -> Unit)? = null

  // ---- observers ---------------------------------------------------------------------------

  fun addListener(listener: (List<ExplainItem>) -> Unit) {
    listeners.add(listener)
    listener(snapshot())
  }

  fun removeListener(listener: (List<ExplainItem>) -> Unit) {
    listeners.remove(listener)
  }

  fun snapshot(): List<ExplainItem> = synchronized(lock) { items }

  private fun publish(newItems: List<ExplainItem>) {
    synchronized(lock) { items = newItems }
    main.post { listeners.forEach { it(newItems) } }
  }

  private fun update(index: Int, change: (ExplainItem) -> ExplainItem) {
    val next = synchronized(lock) {
      if (index !in items.indices) return
      items.toMutableList().also { it[index] = change(it[index]) }.also { items = it }
    }
    main.post { listeners.forEach { it(next) } }
  }

  // ---- build log collection ----------------------------------------------------------------

  /** Called at the start of every build. */
  fun onBuildStarted() {
    synchronized(lock) { logBuffer.setLength(0) }
    job?.cancel()
    publish(emptyList())
  }

  /** Called for every line of build output. Cheap; safe from any thread. */
  fun appendLog(line: String?) {
    if (line == null) return
    synchronized(lock) {
      logBuffer.append(line).append('\n')
      if (logBuffer.length > MAX_LOG_CHARS) {
        logBuffer.delete(0, logBuffer.length - MAX_LOG_CHARS / 2)
      }
    }
  }

  // ---- explaining --------------------------------------------------------------------------

  private fun userLanguage() = AnswerLanguage.fromLocaleCode(Locale.getDefault().language)

  /** Called when a build failed. */
  fun onBuildFailed(context: Context) {
    val app = context.applicationContext
    watchModel(app)

    job?.cancel()
    job = scope.launch {
      // The last lines of Gradle output can still be in flight when the failure callback fires.
      delay(LOG_SETTLE_MS)
      if (prepare(app)) runExplanations(app)
    }
  }

  /**
   * Parses the collected log and publishes the first state (instant hints).
   * @return true when a model should now be run
   */
  private fun prepare(app: Context): Boolean {
    val output = synchronized(lock) { logBuffer.toString() }
    val errors = BuildErrorParser.parse(output)
      .distinctBy { it.message }
      .take(MAX_ERRORS_TO_EXPLAIN)
    if (errors.isEmpty()) {
      publish(emptyList())
      return false
    }

    val mode = AiSettings.mode(app)
    val lang = userLanguage()
    val ready = when {
      mode.isLocal -> ModelManager.readyModels(app, mode) != null
      mode == AiMode.ONLINE -> AiSettings.isOnlineReady(app)
      else -> false
    }
    val initial = when {
      mode == AiMode.NONE -> ItemStatus.HINT_ONLY
      mode == AiMode.ONLINE && !ready -> ItemStatus.HINT_ONLY
      ready -> ItemStatus.QUEUED
      else -> ItemStatus.WAITING_MODEL
    }

    publish(errors.map {
      ExplainItem(
        error = it,
        hint = ErrorKnowledgeBase.match(it)?.hint(lang),
        status = initial,
        canFix = mode.canFix && it.file != null && it.line != null,
      )
    })
    return ready
  }

  /** When the models finish installing after a failed build, explain what is still waiting. */
  private fun watchModel(app: Context) {
    if (modelListener != null) return
    val l: (ModelState) -> Unit = { state ->
      if (state is ModelState.Ready && snapshot().any { it.status == ItemStatus.WAITING_MODEL }) {
        publish(snapshot().map {
          if (it.status == ItemStatus.WAITING_MODEL) it.copy(status = ItemStatus.QUEUED) else it
        })
        job?.cancel()
        job = scope.launch { runExplanations(app) }
      }
    }
    modelListener = l
    ModelManager.addListener(l)
  }

  private fun failureText(t: Throwable) = t.message ?: t.javaClass.simpleName

  private suspend fun runExplanations(app: Context) {
    val mode = AiSettings.mode(app)
    val lang = userLanguage()
    val builder = PromptBuilder()
    val count = snapshot().size

    when (mode) {
      AiMode.ONLINE -> for (i in 0 until count) {
        val item = snapshot().getOrNull(i) ?: break
        if (item.status != ItemStatus.QUEUED) continue
        update(i) { it.copy(status = ItemStatus.RUNNING, ai = "") }
        try {
          val p = builder.explain(item.error, lang, PromptBuilder.ONLINE_MAX_FIX_PROMPT_CHARS)
          val answer = OnlineClient.complete(app, p.system, p.user, ONLINE_EXPLAIN_TOKENS)
          update(i) { it.copy(ai = answer, status = ItemStatus.DONE) }
        } catch (e: CancellationException) {
          throw e
        } catch (t: Throwable) {
          log.warn("Online explanation failed", t)
          update(i) { it.copy(status = ItemStatus.FAILED, failure = failureText(t)) }
        }
      }
      AiMode.BASIC, AiMode.HEBREW, AiMode.HEAVY -> runLocal(app, mode, lang, builder, count)
      AiMode.NONE -> Unit
    }
  }

  private suspend fun runLocal(
    app: Context,
    mode: AiMode,
    lang: AnswerLanguage,
    builder: PromptBuilder,
    count: Int,
  ) {
    val models = ModelManager.readyModels(app, mode) ?: return
    val first = models[if (mode == AiMode.HEAVY) ModelRole.FIXER else ModelRole.EXPLAINER] ?: return
    val translate = mode == AiMode.HEBREW && lang == AnswerLanguage.HEBREW
    // Small models answer reliably in English; Hebrew comes from the translator (or the heavy model).
    val answerLang = if (mode == AiMode.HEAVY) lang else AnswerLanguage.ENGLISH
    val maxChars =
      if (mode == AiMode.HEAVY) HEAVY_EXPLAIN_PROMPT_CHARS else PromptBuilder.DEFAULT_MAX_PROMPT_CHARS

    modelLock.withLock {
      // Phase 1: explain every error with the first model.
      val engine = LlmEngine(app)
      val explained = mutableListOf<Int>()
      try {
        for (i in 0 until count) {
          val item = snapshot().getOrNull(i) ?: break
          if (item.status != ItemStatus.QUEUED) continue
          update(i) { it.copy(status = ItemStatus.RUNNING, ai = "") }
          try {
            val prompt = builder.build(item.error, answerLang, first.spec.template, maxChars)
            val answer = engine.generate(first, prompt) { partial -> update(i) { it.copy(ai = partial) } }
            if (translate) {
              update(i) { it.copy(ai = answer, status = ItemStatus.TRANSLATING) }
              explained += i
            } else {
              update(i) { it.copy(ai = answer, status = ItemStatus.DONE) }
            }
          } catch (e: CancellationException) {
            throw e
          } catch (t: Throwable) {
            log.error("AI explanation failed", t)
            update(i) { it.copy(status = ItemStatus.FAILED, failure = failureText(t)) }
            if (t is Error) { // native library / memory problem: do not keep trying
              markRemainingFailed(t)
              return
            }
          }
        }
      } finally {
        engine.release()
      }

      // Phase 2 (Hebrew mode): translate the English answers with the second model.
      val translator = models[ModelRole.TRANSLATOR]
      if (translate && translator != null && explained.isNotEmpty()) {
        val engine2 = LlmEngine(app)
        try {
          for (i in explained) {
            val english = snapshot().getOrNull(i)?.ai ?: continue
            try {
              val prompt = builder.buildTranslate(english, translator.spec.template)
              val hebrew = engine2.generate(translator, prompt) { partial ->
                update(i) { it.copy(ai = partial) }
              }
              update(i) { it.copy(ai = hebrew, status = ItemStatus.DONE) }
            } catch (e: CancellationException) {
              throw e
            } catch (t: Throwable) {
              // Keep the English explanation; it is still useful.
              log.error("Translation failed", t)
              update(i) { it.copy(ai = english, status = ItemStatus.DONE, failure = failureText(t)) }
              if (t is Error) break
            }
          }
        } finally {
          engine2.release()
        }
      }
    }
  }

  private fun markRemainingFailed(t: Throwable) {
    publish(snapshot().map {
      if (it.status == ItemStatus.QUEUED) it.copy(status = ItemStatus.FAILED, failure = failureText(t)) else it
    })
  }

  // ---- automatic fix -----------------------------------------------------------------------

  /** Asks the heavy local model, or the online model, to propose a fix for item [index]. */
  fun requestFix(context: Context, index: Int) {
    val app = context.applicationContext
    val item = snapshot().getOrNull(index) ?: return
    if (!item.canFix || item.fix is FixState.Generating) return
    update(index) { it.copy(fix = FixState.Generating) }

    scope.launch {
      try {
        val state = generateFix(app, item.error)
        update(index) { it.copy(fix = state) }
      } catch (e: CancellationException) {
        throw e
      } catch (t: Throwable) {
        log.error("Fix generation failed", t)
        update(index) { it.copy(fix = FixState.Failed(failureText(t))) }
      }
    }
  }

  private suspend fun generateFix(app: Context, error: BuildError): FixState {
    val path = error.file ?: return FixState.Failed("The error has no file.")
    val mode = AiSettings.mode(app)
    val builder = PromptBuilder()

    val response: String = when (mode) {
      AiMode.ONLINE -> {
        if (!AiSettings.isOnlineReady(app)) return FixState.Failed("Online mode is not configured.")
        val p = builder.fix(error, PromptBuilder.ONLINE_MAX_FIX_PROMPT_CHARS)
          ?: return FixState.Failed("Cannot read the file around the error.")
        OnlineClient.complete(app, p.system, p.user, ONLINE_FIX_TOKENS)
      }
      AiMode.HEAVY -> {
        val model = ModelManager.readyModels(app, mode)?.get(ModelRole.FIXER)
          ?: return FixState.Failed("The heavy model is not installed yet.")
        val p = builder.fix(error, FIX_PROMPT_CHARS_LOCAL)
          ?: return FixState.Failed("The file is too long to send to the local model around this error.")
        modelLock.withLock {
          val engine = LlmEngine(app)
          try {
            engine.generate(model, p.format(model.spec.template)) { }
          } finally {
            engine.release()
          }
        }
      }
      else -> return FixState.Failed("Automatic fixes need the heavy or the online mode.")
    }

    return when (val parsed = FixParser.parse(response)) {
      is FixParseResult.NoFix -> FixState.NoFix(parsed.reason)
      is FixParseResult.Invalid -> FixState.Failed(parsed.message)
      is FixParseResult.Blocks -> {
        // Validate against the real file now, so the user is never offered a fix that cannot apply.
        val text = try {
          File(path).readText()
        } catch (e: Exception) {
          return FixState.Failed("Cannot read the file.")
        }
        when (val applied = FixParser.apply(text, parsed.blocks)) {
          is ApplyResult.Failure -> FixState.Failed(applied.message)
          is ApplyResult.Success -> FixState.Proposed(parsed.blocks, FixParser.preview(parsed.blocks))
        }
      }
    }
  }

  /**
   * Computes the new text for item [index] given the file's [currentText] (the editor's text when
   * the file is open, so unsaved edits are not lost). Does not touch any file.
   */
  fun computeFix(index: Int, currentText: String): ApplyResult {
    val fix = snapshot().getOrNull(index)?.fix as? FixState.Proposed
      ?: return ApplyResult.Failure("There is no fix to apply.")
    return FixParser.apply(currentText, fix.blocks)
  }

  fun markApplied(index: Int, file: File, original: String) {
    update(index) {
      val blocks = (it.fix as? FixState.Proposed)?.blocks ?: emptyList()
      it.copy(fix = FixState.Applied(file, original, blocks))
    }
  }

  fun markUndone(index: Int) {
    update(index) { it.copy(fix = FixState.None) }
  }
}
