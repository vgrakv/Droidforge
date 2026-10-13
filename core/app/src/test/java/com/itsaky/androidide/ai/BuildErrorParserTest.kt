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

class BuildErrorParserTest {

  @Test
  fun `parses javac error with detail lines`() {
    val out = """
      > Task :app:compileDebugJavaWithJavac FAILED
      /home/u/App/src/main/java/com/x/Main.java:12: error: cannot find symbol
              foo.bar();
              ^
        symbol:   variable foo
        location: class Main
      1 error
    """.trimIndent()

    val errors = BuildErrorParser.parse(out)
    assertThat(errors).hasSize(1)
    val e = errors[0]
    assertThat(e.source).isEqualTo(ErrorSource.JAVAC)
    assertThat(e.file).isEqualTo("/home/u/App/src/main/java/com/x/Main.java")
    assertThat(e.line).isEqualTo(12)
    assertThat(e.message).isEqualTo("cannot find symbol")
    assertThat(e.details).contains("symbol:   variable foo")
    assertThat(e.details).contains("location: class Main")
  }

  @Test
  fun `parses kotlin errors in both formats`() {
    val out = """
      e: file:///home/u/App/Main.kt:7:13 Unresolved reference: foo
      e: /home/u/App/Old.kt: (3, 5): Type mismatch: inferred type is String but Int was expected
    """.trimIndent()

    val errors = BuildErrorParser.parse(out)
    assertThat(errors).hasSize(2)
    assertThat(errors[0].file).isEqualTo("/home/u/App/Main.kt")
    assertThat(errors[0].line).isEqualTo(7)
    assertThat(errors[0].column).isEqualTo(13)
    assertThat(errors[0].message).isEqualTo("Unresolved reference: foo")
    assertThat(errors[1].line).isEqualTo(3)
    assertThat(errors[1].column).isEqualTo(5)
  }

  @Test
  fun `parses aapt errors`() {
    val out = "/home/u/App/res/layout/main.xml:10: error: attribute foo (aka x:foo) not found."
    val e = BuildErrorParser.parse(out).single()
    assertThat(e.source).isEqualTo(ErrorSource.AAPT)
    assertThat(e.line).isEqualTo(10)
  }

  @Test
  fun `falls back to the gradle failure block when there is no source error`() {
    val out = """
      FAILURE: Build failed with an exception.

      * What went wrong:
      Could not resolve all dependencies for configuration ':app:debugRuntimeClasspath'.
      > Could not resolve com.foo:bar:1.0.
        Required by: project :app

      * Try:
      > Run with --stacktrace option to get the stack trace.
    """.trimIndent()

    val e = BuildErrorParser.parse(out).single()
    assertThat(e.source).isEqualTo(ErrorSource.GRADLE)
    assertThat(e.message).startsWith("Could not resolve all dependencies")
    assertThat(e.details).isNotEmpty()
  }

  @Test
  fun `gradle noise is ignored when a source error exists`() {
    val out = """
      /a/B.java:1: error: ';' expected
      FAILURE: Build failed with an exception.

      * What went wrong:
      Execution failed for task ':app:compileDebugJavaWithJavac'.
      > Compilation failed; see the compiler error output for details.
    """.trimIndent()

    val errors = BuildErrorParser.parse(out)
    assertThat(errors).hasSize(1)
    assertThat(errors[0].source).isEqualTo(ErrorSource.JAVAC)
  }

  @Test
  fun `duplicates are dropped and the limit is respected`() {
    val one = "/a/B.java:1: error: cannot find symbol\n"
    assertThat(BuildErrorParser.parse(one + one)).hasSize(1)

    val many = (1..30).joinToString("\n") { "/a/B.java:$it: error: boom $it" }
    assertThat(BuildErrorParser.parse(many, limit = 5)).hasSize(5)
  }

  @Test
  fun `empty and successful output give no errors`() {
    assertThat(BuildErrorParser.parse("")).isEmpty()
    assertThat(BuildErrorParser.parse("BUILD SUCCESSFUL in 3s")).isEmpty()
  }
}
