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

import java.io.File

/** Reads the lines around an error. Abstracted so the prompt logic can be unit-tested. */
fun interface SourceReader {
  /** Returns all lines of [path], or null when the file cannot be read. */
  fun readLines(path: String): List<String>?

  companion object {
    val FILE_SYSTEM = SourceReader { path ->
      try {
        val f = File(path)
        // Never feed huge or binary files to the prompt.
        if (f.isFile && f.length() < 512 * 1024) f.readLines() else null
      } catch (e: Exception) {
        null
      }
    }
  }
}

/** A prompt split into its system and user parts (online APIs take them separately). */
data class Prompt(val system: String, val user: String) {
  /** The single string a local model expects, using its chat [template]. */
  fun format(template: ChatTemplate): String = template.format(system, user)
}

/**
 * Builds the prompts sent to the models (local or online).
 *
 * Local models have small context windows (1280 or 4096 tokens for prompt + answer), so every
 * prompt is budgeted by characters (~3 characters per token for code).
 */
class PromptBuilder(
  private val reader: SourceReader = SourceReader.FILE_SYSTEM,
  private val contextLines: Int = 6,
) {

  // ---- explain -----------------------------------------------------------------------------

  /** Prompt that asks for a short explanation of [error] in [lang]. */
  fun explain(
    error: BuildError,
    lang: AnswerLanguage,
    maxChars: Int = DEFAULT_MAX_PROMPT_CHARS,
  ): Prompt {
    val system = buildString {
      append("You are a senior Android developer helping a beginner fix a build error. ")
      append("Be short and concrete. Answer in ${lang.promptName}. ")
      append("Use exactly this format:\n")
      append("1. What it means: one or two sentences.\n")
      append("2. How to fix: numbered steps, with a very short code example only if needed.\n")
      append("Keep identifiers, keywords and code in English. Do not invent files or APIs.")
    }
    return Prompt(system, buildUserMessage(error, maxChars - system.length - TEMPLATE_OVERHEAD))
  }

  /** [explain] formatted for a local model. */
  fun build(
    error: BuildError,
    lang: AnswerLanguage,
    template: ChatTemplate = ChatTemplate.CHATML,
    maxChars: Int = DEFAULT_MAX_PROMPT_CHARS,
  ): String = explain(error, lang, maxChars).format(template)

  internal fun buildUserMessage(error: BuildError, budget: Int): String {
    val header = buildString {
      append("Tool: ${error.source.name.lowercase()}\n")
      if (error.file != null) {
        append("File: ${error.file.substringAfterLast('/')}")
        if (error.line != null) append(", line ${error.line}")
        append('\n')
      }
      append("Error: ${error.message.take(300)}\n")
    }

    val known = ErrorKnowledgeBase.match(error)?.en
    val knownBlock = if (known != null) "Known cause: $known\n" else ""

    val details = error.details.filter { it.isNotBlank() }.take(3)
    val detailBlock =
      if (details.isEmpty()) "" else "Compiler notes:\n" + details.joinToString("\n") + "\n"

    val tail = "Explain this error and how to fix it."

    // Everything except the code snippet is mandatory; the snippet gets the remaining budget.
    val fixed = header + knownBlock + detailBlock + tail
    val snippet = snippet(error, (budget - fixed.length - 40).coerceAtLeast(0))

    return buildString {
      append(header)
      if (snippet.isNotEmpty()) append("Code:\n").append(snippet).append('\n')
      append(knownBlock)
      append(detailBlock)
      append(tail)
    }
  }

  private fun snippet(error: BuildError, budget: Int): String {
    val path = error.file ?: return ""
    val line = error.line ?: return ""
    if (budget < 60) return ""
    val all = reader.readLines(path) ?: return ""
    if (line < 1 || line > all.size) return ""

    var from = (line - 1 - contextLines).coerceAtLeast(0)
    var to = (line - 1 + contextLines).coerceAtMost(all.size - 1)

    fun render(): String = (from..to).joinToString("\n") { idx ->
      val marker = if (idx == line - 1) ">> " else "   "
      marker + all[idx].trimEnd().take(160)
    }

    var text = render()
    // Shrink symmetrically around the error line until the snippet fits the budget.
    while (text.length > budget && (to > line - 1 || from < line - 1)) {
      if (to - (line - 1) >= (line - 1) - from && to > line - 1) to-- else from++
      text = render()
    }
    return if (text.length <= budget) text else ""
  }

  // ---- translate ---------------------------------------------------------------------------

  /** Prompt that asks a model to translate an English explanation into Hebrew. */
  fun translate(englishText: String, maxChars: Int = 6000): Prompt {
    val system = "You are a professional technical translator. Translate the user's text into " +
      "clear, natural Hebrew for a beginner programmer. Keep code, identifiers, file names and " +
      "programming keywords exactly as written, in English. Keep the numbered structure. " +
      "Output only the translation."
    val budget = (maxChars - system.length - TEMPLATE_OVERHEAD).coerceAtLeast(200)
    return Prompt(system, englishText.trim().take(budget))
  }

  fun buildTranslate(
    englishText: String,
    template: ChatTemplate = ChatTemplate.CHATML,
    maxChars: Int = 6000,
  ): String = translate(englishText, maxChars).format(template)

  // ---- fix ---------------------------------------------------------------------------------

  /**
   * Prompt that asks for a minimal code fix as SEARCH/REPLACE blocks (see [FixParser]).
   * Returns null when the error has no readable file/line (nothing to patch).
   */
  fun fix(
    error: BuildError,
    maxChars: Int = DEFAULT_MAX_FIX_PROMPT_CHARS,
  ): Prompt? {
    val path = error.file ?: return null
    val line = error.line ?: return null
    val all = reader.readLines(path) ?: return null
    if (line < 1 || line > all.size) return null

    val system = buildString {
      append("You are an expert Android developer. Fix the build error by editing the given file. ")
      append("Reply ONLY with one or more edit blocks in exactly this format:\n")
      append("<<<<<<< SEARCH\n(lines copied exactly from the file)\n=======\n(replacement lines)\n>>>>>>> REPLACE\n")
      append("Rules: SEARCH must match the file character for character and be unique; ")
      append("keep the change as small as possible; do not touch unrelated code; ")
      append("no explanations outside the blocks. ")
      append("If the error cannot be fixed by editing this file, reply with: NOFIX: <short reason>.")
    }

    val header = buildString {
      append("File: ${path.substringAfterLast('/')}\n")
      append("Error at line $line: ${error.message.take(300)}\n")
      val details = error.details.filter { it.isNotBlank() }.take(3)
      if (details.isNotEmpty()) append("Compiler notes:\n").append(details.joinToString("\n")).append('\n')
      ErrorKnowledgeBase.match(error)?.let { append("Known cause: ${it.en}\n") }
    }
    val tail = "Return the edit blocks now."
    val budget = (maxChars - system.length - TEMPLATE_OVERHEAD - header.length - tail.length - 60)
      .coerceAtLeast(0)

    var from = (line - 1 - FIX_CONTEXT_LINES).coerceAtLeast(0)
    var to = (line - 1 + FIX_CONTEXT_LINES).coerceAtMost(all.size - 1)
    fun render() = (from..to).joinToString("\n") { all[it].trimEnd() }
    var code = render()
    while (code.length > budget && (to > line - 1 || from < line - 1)) {
      if (to - (line - 1) >= (line - 1) - from && to > line - 1) to-- else from++
      code = render()
    }
    if (code.length > budget) return null

    val user = header + "Code (lines ${from + 1}-${to + 1} of the file):\n" + code + "\n" + tail
    return Prompt(system, user)
  }

  fun buildFix(
    error: BuildError,
    template: ChatTemplate = ChatTemplate.PHI,
    maxChars: Int = DEFAULT_MAX_FIX_PROMPT_CHARS,
  ): String? = fix(error, maxChars)?.format(template)

  companion object {
    /** ~2200 chars ≈ 700 tokens. Leaves room for the answer in a 1280-token window. */
    const val DEFAULT_MAX_PROMPT_CHARS = 2200

    /** ~7000 chars ≈ 2300 tokens of a 4096-token window; the rest is for the answer. */
    const val DEFAULT_MAX_FIX_PROMPT_CHARS = 7000

    /** Online models have big windows. */
    const val ONLINE_MAX_FIX_PROMPT_CHARS = 30000

    private const val TEMPLATE_OVERHEAD = 60
    private const val FIX_CONTEXT_LINES = 60
  }
}
