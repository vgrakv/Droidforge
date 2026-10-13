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

/** One edit proposed by a model: replace [search] by [replace] in the target file. */
data class EditBlock(val search: String, val replace: String)

sealed class FixParseResult {
  data class Blocks(val blocks: List<EditBlock>) : FixParseResult()

  /** The model said the error cannot be fixed by editing this file. */
  data class NoFix(val reason: String) : FixParseResult()

  data class Invalid(val message: String) : FixParseResult()
}

sealed class ApplyResult {
  data class Success(val newText: String) : ApplyResult()
  data class Failure(val message: String) : ApplyResult()
}

/**
 * Parses and applies "SEARCH/REPLACE" edit blocks produced by a model.
 *
 * The model never chooses the file: edits always apply to the file the error is in. A block is
 * applied only when its SEARCH text is found exactly once, so a wrong guess fails safely instead
 * of corrupting the file.
 */
object FixParser {

  private val SEARCH_MARK = Regex("""^\s*<{5,9}\s*SEARCH\s*$""")
  private val DIVIDER = Regex("""^\s*={5,9}\s*$""")
  private val REPLACE_MARK = Regex("""^\s*>{5,9}\s*REPLACE\s*$""")

  fun parse(response: String): FixParseResult {
    val text = response.replace("\r\n", "\n").trim()
    Regex("""(?i)^NOFIX\s*:?\s*(.*)$""", RegexOption.DOT_MATCHES_ALL).find(text)?.let {
      return FixParseResult.NoFix(it.groupValues[1].trim().ifEmpty { "-" })
    }

    val blocks = mutableListOf<EditBlock>()
    val search = StringBuilder()
    val replace = StringBuilder()
    var state = 0 // 0 = outside, 1 = in SEARCH, 2 = in REPLACE

    fun append(sb: StringBuilder, line: String) {
      if (sb.isNotEmpty()) sb.append('\n')
      sb.append(line)
    }

    for (line in text.split('\n')) {
      when (state) {
        0 -> if (SEARCH_MARK.matches(line)) {
          search.setLength(0)
          replace.setLength(0)
          state = 1
        }
        1 -> if (DIVIDER.matches(line)) state = 2 else append(search, line)
        2 -> if (REPLACE_MARK.matches(line)) {
          blocks += EditBlock(search.toString(), replace.toString())
          state = 0
        } else {
          append(replace, line)
        }
      }
    }

    if (state != 0) return FixParseResult.Invalid("The edit block is incomplete.")
    if (blocks.isEmpty()) return FixParseResult.Invalid("The model did not return an edit.")
    if (blocks.any { it.search.isBlank() }) return FixParseResult.Invalid("An edit block has no SEARCH text.")
    return FixParseResult.Blocks(blocks)
  }

  /** Applies all [blocks] to [original]; fails (changing nothing) if any block cannot be placed. */
  fun apply(original: String, blocks: List<EditBlock>): ApplyResult {
    val crlf = original.contains("\r\n")
    var text = original.replace("\r\n", "\n")

    for ((i, block) in blocks.withIndex()) {
      val n = i + 1
      val exact = indicesOf(text, block.search)
      when {
        exact.size == 1 -> {
          text = text.substring(0, exact[0]) + block.replace + text.substring(exact[0] + block.search.length)
        }
        exact.size > 1 -> return ApplyResult.Failure("Edit $n matches ${exact.size} places in the file.")
        else -> {
          // Models often get indentation or trailing spaces slightly wrong: retry line by line.
          val replaced = replaceIgnoringIndent(text, block)
            ?: return ApplyResult.Failure("Edit $n does not match the file.")
          text = replaced
        }
      }
    }
    return ApplyResult.Success(if (crlf) text.replace("\n", "\r\n") else text)
  }

  /** A short human-readable preview ("-" removed, "+" added) of [blocks]. */
  fun preview(blocks: List<EditBlock>): String = blocks.joinToString("\n…\n") { b ->
    val removed = b.search.split('\n').joinToString("\n") { "- $it" }
    val added = if (b.replace.isEmpty()) "" else "\n" + b.replace.split('\n').joinToString("\n") { "+ $it" }
    removed + added
  }

  private fun indicesOf(text: String, needle: String): List<Int> {
    if (needle.isEmpty()) return emptyList()
    val result = mutableListOf<Int>()
    var from = 0
    while (true) {
      val i = text.indexOf(needle, from)
      if (i < 0) break
      result += i
      from = i + 1
    }
    return result
  }

  private fun replaceIgnoringIndent(text: String, block: EditBlock): String? {
    val lines = text.split('\n')
    val want = block.search.split('\n').map { it.trim() }
    if (want.all { it.isEmpty() }) return null

    val hits = mutableListOf<Int>()
    for (start in 0..lines.size - want.size) {
      if (want.indices.all { lines[start + it].trim() == want[it] }) hits += start
    }
    if (hits.size != 1) return null

    val start = hits[0]
    // Re-indent the replacement relative to the first matched line.
    val indent = lines[start].takeWhile { it == ' ' || it == '\t' }
    val replaced = block.replace.split('\n').map { if (it.isBlank()) it else indent + it.trimStart() }
    val out = lines.subList(0, start) + (if (block.replace.isEmpty()) emptyList() else replaced) +
      lines.subList(start + want.size, lines.size)
    return out.joinToString("\n")
  }
}
