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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptBuilderTest {

  private val source = (1..40).map { "line $it of the file" }
  private val reader = SourceReader { source }

  private val error = BuildError(
    ErrorSource.JAVAC, "cannot find symbol", "/proj/src/Main.java", 20,
    details = listOf("symbol:   variable foo", "location: class Main"),
  )

  @Test
  fun `prompt is chatml with system, user and open assistant turn`() {
    val p = PromptBuilder(reader).build(error, AnswerLanguage.HEBREW)
    assertThat(p).startsWith("<|im_start|>system\n")
    assertThat(p).contains("<|im_end|>\n<|im_start|>user\n")
    assertThat(p).endsWith("<|im_start|>assistant\n")
    assertThat(p).contains("Answer in Hebrew")
  }

  @Test
  fun `prompt contains the error, location, marked line and known cause`() {
    val p = PromptBuilder(reader).build(error, AnswerLanguage.ENGLISH)
    assertThat(p).contains("Error: cannot find symbol")
    assertThat(p).contains("File: Main.java, line 20")
    assertThat(p).contains(">> line 20 of the file")
    assertThat(p).contains("Known cause:")
    assertThat(p).contains("symbol:   variable foo")
    // The full path is not leaked into the prompt, only the file name.
    assertThat(p).doesNotContain("/proj/src")
  }

  @Test
  fun `prompt always fits the budget`() {
    val huge = source.map { it.repeat(40) }
    val builder = PromptBuilder({ huge })
    val longError = error.copy(
      message = "x".repeat(2000),
      details = List(10) { "d".repeat(500) },
    )
    val p = builder.build(longError, AnswerLanguage.ENGLISH, maxChars = 2200)
    assertThat(p.length).isAtMost(2200 + 300)
  }

  @Test
  fun `snippet shrinks around the error line when the budget is small`() {
    val small = PromptBuilder(reader).build(error, AnswerLanguage.ENGLISH, maxChars = 900)
    val big = PromptBuilder(reader).build(error, AnswerLanguage.ENGLISH, maxChars = 2200)
    assertThat(small).contains(">> line 20 of the file")
    assertThat(small.length).isLessThan(big.length)
  }

  @Test
  fun `missing file or line gives a prompt without code`() {
    val noFile = PromptBuilder({ null }).build(error, AnswerLanguage.ENGLISH)
    assertThat(noFile).doesNotContain("Code:")
    val noLine = PromptBuilder(reader).build(error.copy(file = null, line = null), AnswerLanguage.ENGLISH)
    assertThat(noLine).doesNotContain("Code:")
    val outOfRange = PromptBuilder(reader).build(error.copy(line = 999), AnswerLanguage.ENGLISH)
    assertThat(outOfRange).doesNotContain("Code:")
  }

  @Test
  fun `phi template is used when requested`() {
    val p = PromptBuilder(reader).build(error, AnswerLanguage.HEBREW, ChatTemplate.PHI)
    assertThat(p).startsWith("<|system|>")
    assertThat(p).contains("<|end|><|user|>")
    assertThat(p).endsWith("<|assistant|>")
  }

  @Test
  fun `translate prompt carries the text and the rules`() {
    val prompt = PromptBuilder(reader).translate("1. What it means: x is missing.")
    assertThat(prompt.system).contains("Hebrew")
    assertThat(prompt.system).contains("exactly as written")
    assertThat(prompt.user).isEqualTo("1. What it means: x is missing.")
    assertThat(PromptBuilder(reader).buildTranslate("hello")).endsWith("<|im_start|>assistant\n")
  }

  @Test
  fun `translate prompt truncates very long text`() {
    val p = PromptBuilder(reader).translate("a".repeat(50_000), maxChars = 1000)
    assertThat(p.user.length).isAtMost(1000)
  }

  @Test
  fun `fix prompt asks for edit blocks and contains the code without markers`() {
    val p = PromptBuilder(reader).fix(error)!!
    assertThat(p.system).contains("<<<<<<< SEARCH")
    assertThat(p.system).contains("NOFIX")
    assertThat(p.user).contains("Error at line 20")
    assertThat(p.user).contains("line 20 of the file")
    assertThat(p.user).doesNotContain(">>")
    assertThat(p.user).contains("lines ")
  }

  @Test
  fun `fix prompt is null when there is nothing to patch`() {
    assertThat(PromptBuilder({ null }).fix(error)).isNull()
    assertThat(PromptBuilder(reader).fix(error.copy(file = null))).isNull()
    assertThat(PromptBuilder(reader).fix(error.copy(line = 500))).isNull()
  }

  @Test
  fun `fix prompt respects a small budget`() {
    val big = (1..400).map { "x".repeat(100) + it }
    val p = PromptBuilder({ big }).fix(error.copy(line = 200), maxChars = 3000)!!
    assertThat(p.system.length + p.user.length).isAtMost(3000)
    assertThat(p.user).contains(big[199])
  }
}
