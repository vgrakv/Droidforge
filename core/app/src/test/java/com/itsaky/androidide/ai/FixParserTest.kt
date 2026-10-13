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

class FixParserTest {

  private val file = """
    package a;

    public class Main {
        void run() {
            int x = "hello";
            System.out.println(x);
        }
    }
  """.trimIndent()

  private fun blockOf(search: String, replace: String) =
    "<<<<<<< SEARCH\n$search\n=======\n$replace\n>>>>>>> REPLACE"

  @Test
  fun `parses a block, also inside a code fence with chatter`() {
    val response = "Here is the fix:\n```java\n" +
      blockOf("int x = \"hello\";", "String x = \"hello\";") + "\n```\nDone."
    val parsed = FixParser.parse(response) as FixParseResult.Blocks
    assertThat(parsed.blocks).hasSize(1)
    assertThat(parsed.blocks[0].search).isEqualTo("int x = \"hello\";")
    assertThat(parsed.blocks[0].replace).isEqualTo("String x = \"hello\";")
  }

  @Test
  fun `parses several blocks and longer or shorter markers`() {
    val response = blockOf("a", "b") + "\n\n" +
      "<<<<< SEARCH\nc\n=====\nd\n>>>>> REPLACE"
    val parsed = FixParser.parse(response) as FixParseResult.Blocks
    assertThat(parsed.blocks).hasSize(2)
    assertThat(parsed.blocks[1].replace).isEqualTo("d")
  }

  @Test
  fun `nofix and invalid answers`() {
    assertThat(FixParser.parse("NOFIX: add the dependency in build.gradle"))
      .isEqualTo(FixParseResult.NoFix("add the dependency in build.gradle"))
    assertThat(FixParser.parse("Sure! Just change the type.")).isInstanceOf(FixParseResult.Invalid::class.java)
    assertThat(FixParser.parse("<<<<<<< SEARCH\nx\n=======\ny")).isInstanceOf(FixParseResult.Invalid::class.java)
    assertThat(FixParser.parse("<<<<<<< SEARCH\n\n=======\ny\n>>>>>>> REPLACE"))
      .isInstanceOf(FixParseResult.Invalid::class.java)
  }

  @Test
  fun `applies an exact edit`() {
    val blocks = listOf(EditBlock("int x = \"hello\";", "String x = \"hello\";"))
    val r = FixParser.apply(file, blocks) as ApplyResult.Success
    assertThat(r.newText).contains("String x = \"hello\";")
    assertThat(r.newText).doesNotContain("int x")
  }

  @Test
  fun `tolerates wrong indentation and keeps the file's indentation`() {
    val blocks = listOf(EditBlock("int x = \"hello\";", "String x = \"hello\";"))
    val r = FixParser.apply(file, blocks) as ApplyResult.Success
    assertThat(r.newText).contains("        String x = \"hello\";")

    val sloppy = listOf(EditBlock("      int x = \"hello\";   ", "String x = \"hello\";"))
    val r2 = FixParser.apply(file, sloppy) as ApplyResult.Success
    assertThat(r2.newText).contains("        String x = \"hello\";")
  }

  @Test
  fun `fails safely when the text is missing or ambiguous`() {
    assertThat(FixParser.apply(file, listOf(EditBlock("nothing like this", "x")))).isInstanceOf(ApplyResult.Failure::class.java)
    val twice = "a();\nb();\na();"
    assertThat(FixParser.apply(twice, listOf(EditBlock("a();", "c();")))).isInstanceOf(ApplyResult.Failure::class.java)
  }

  @Test
  fun `all edits are applied or none`() {
    val blocks = listOf(EditBlock("int x", "long x"), EditBlock("does not exist", "y"))
    assertThat(FixParser.apply(file, blocks)).isInstanceOf(ApplyResult.Failure::class.java)
  }

  @Test
  fun `crlf files stay crlf`() {
    val crlf = file.replace("\n", "\r\n")
    val r = FixParser.apply(crlf, listOf(EditBlock("int x", "long x"))) as ApplyResult.Success
    assertThat(r.newText).contains("long x")
    assertThat(r.newText).contains("\r\n")
    assertThat(r.newText.replace("\r\n", "")).doesNotContain("\n")
  }

  @Test
  fun `multi-line edits and preview`() {
    val blocks = listOf(EditBlock("int x = \"hello\";\n        System.out.println(x);", "String x = \"hello\";\n        System.out.println(x);"))
    val r = FixParser.apply(file, blocks) as ApplyResult.Success
    assertThat(r.newText).contains("String x")
    val preview = FixParser.preview(blocks)
    assertThat(preview).contains("- int x = \"hello\";")
    assertThat(preview).contains("+ String x = \"hello\";")
  }
}
