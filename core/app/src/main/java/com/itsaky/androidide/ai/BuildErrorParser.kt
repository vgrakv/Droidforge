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

/** Which tool produced the error. */
enum class ErrorSource { JAVAC, KOTLINC, AAPT, GRADLE }

/**
 * A single, structured build error.
 *
 * @property details Extra lines that belong to the error (javac `symbol:`/`location:` lines,
 *   the echoed source line, Gradle "Caused by" lines...). Already trimmed and capped.
 */
data class BuildError(
  val source: ErrorSource,
  val message: String,
  val file: String? = null,
  val line: Int? = null,
  val column: Int? = null,
  val details: List<String> = emptyList(),
) {
  /** Key used to drop duplicates (the same error is often printed twice by Gradle). */
  val dedupKey: String get() = "$source|$file|$line|$message"
}

/**
 * Turns raw Gradle/javac/kotlinc/aapt2 output into [BuildError]s using plain regexes.
 * No AI involved: this step is deterministic and cheap, and it is what gives the small
 * language model a clean, short input.
 */
object BuildErrorParser {

  private const val MAX_DETAIL_LINES = 6
  private const val MAX_DETAIL_LINE_LENGTH = 200

  // /path/Foo.java:12: error: cannot find symbol
  private val JAVAC = Regex("""^(.+\.java):(\d+): error: (.*)$""")

  // e: file:///path/Foo.kt:12:5 Unresolved reference: x      (Kotlin 1.9 / 2.x)
  // e: /path/Foo.kt: (12, 5): Unresolved reference: x         (older Kotlin)
  private val KOTLIN_NEW = Regex("""^e: (?:file://)?(.+?\.kts?):(\d+):(\d+)\s+(.*)$""")
  private val KOTLIN_OLD = Regex("""^e: (?:file://)?(.+?\.kts?): \((\d+), (\d+)\): (.*)$""")

  // /path/res/layout/a.xml:10: error: attribute foo not found.
  private val AAPT_FILE = Regex("""^(?:AAPT: )?(.+\.xml):(\d+)(?::\d+)?: error: (.*)$""")
  private val AAPT_PLAIN = Regex("""^(?:AAPT: )?error: (.*)$""")

  private val GRADLE_WENT_WRONG = "* What went wrong:"
  private val GRADLE_TRY = "* Try:"

  private val NEW_ENTRY = Regex("""^(?:e: |w: |.+\.java:\d+: |.+\.xml:\d+|\* |FAILURE|BUILD )""")

  /** Parses [output] and returns at most [limit] distinct errors, in order of appearance. */
  fun parse(output: String, limit: Int = 20): List<BuildError> {
    val lines = output.lines().map { it.trimEnd('\r') }
    val sourceErrors = mutableListOf<BuildError>()
    var i = 0
    while (i < lines.size) {
      val raw = lines[i]
      val text = raw.trim()
      val parsed = matchSourceError(text)
      if (parsed != null) {
        val details = mutableListOf<String>()
        var j = i + 1
        while (j < lines.size && details.size < MAX_DETAIL_LINES) {
          val next = lines[j]
          if (next.isBlank() || NEW_ENTRY.containsMatchIn(next)) break
          details += next.trim().take(MAX_DETAIL_LINE_LENGTH)
          j++
        }
        sourceErrors += parsed.copy(details = details)
        i = j
        continue
      }
      i++
    }

    val result = LinkedHashMap<String, BuildError>()
    for (e in sourceErrors) result.putIfAbsent(e.dedupKey, e)

    // Gradle-level failures only matter when no source-level error explains the failure.
    if (result.isEmpty()) {
      parseGradleFailure(lines)?.let { result.putIfAbsent(it.dedupKey, it) }
    }
    return result.values.take(limit)
  }

  private fun matchSourceError(text: String): BuildError? {
    JAVAC.matchEntire(text)?.let {
      return BuildError(
        ErrorSource.JAVAC, it.groupValues[3], it.groupValues[1], it.groupValues[2].toIntOrNull()
      )
    }
    KOTLIN_NEW.matchEntire(text)?.let {
      return BuildError(
        ErrorSource.KOTLINC, it.groupValues[4], it.groupValues[1],
        it.groupValues[2].toIntOrNull(), it.groupValues[3].toIntOrNull()
      )
    }
    KOTLIN_OLD.matchEntire(text)?.let {
      return BuildError(
        ErrorSource.KOTLINC, it.groupValues[4], it.groupValues[1],
        it.groupValues[2].toIntOrNull(), it.groupValues[3].toIntOrNull()
      )
    }
    AAPT_FILE.matchEntire(text)?.let {
      return BuildError(
        ErrorSource.AAPT, it.groupValues[3], it.groupValues[1], it.groupValues[2].toIntOrNull()
      )
    }
    AAPT_PLAIN.matchEntire(text)?.let {
      return BuildError(ErrorSource.AAPT, it.groupValues[1])
    }
    return null
  }

  /** Extracts the `* What went wrong:` block of a Gradle failure report. */
  private fun parseGradleFailure(lines: List<String>): BuildError? {
    val start = lines.indexOfFirst { it.trim() == GRADLE_WENT_WRONG }
    if (start < 0) return null
    val block = mutableListOf<String>()
    var j = start + 1
    while (j < lines.size && lines[j].trim() != GRADLE_TRY && !lines[j].startsWith("* ")) {
      if (lines[j].isNotBlank()) block += lines[j].trim().take(MAX_DETAIL_LINE_LENGTH)
      j++
    }
    if (block.isEmpty()) return null
    return BuildError(
      source = ErrorSource.GRADLE,
      message = block.first(),
      details = block.drop(1).take(MAX_DETAIL_LINES),
    )
  }
}
