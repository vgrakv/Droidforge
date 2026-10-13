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

class ErrorKnowledgeBaseTest {

  private fun err(msg: String, vararg details: String) =
    BuildError(ErrorSource.JAVAC, msg, "/a/B.java", 1, details = details.toList())

  @Test
  fun `common errors are recognised`() {
    val cases = mapOf(
      "cannot find symbol" to "missing-symbol",
      "Unresolved reference: foo" to "missing-symbol",
      "package androidx.foo does not exist" to "missing-package",
      "incompatible types: String cannot be converted to int" to "type-mismatch",
      "';' expected" to "syntax",
      "missing return statement" to "missing-return",
      "unreported exception IOException; must be caught or declared to be thrown" to "unreported-exception",
      "Could not resolve com.foo:bar:1.0." to "dependency-resolution",
      "Manifest merger failed with multiple errors" to "manifest-merger",
      "Java heap space" to "out-of-memory",
      "SDK location not found." to "sdk-location",
      "Val cannot be reassigned" to "val-reassign",
      "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type String?" to "nullability",
    )
    for ((msg, id) in cases) {
      assertThat(ErrorKnowledgeBase.match(err(msg))?.id).isEqualTo(id)
    }
  }

  @Test
  fun `unknown errors have no hint`() {
    assertThat(ErrorKnowledgeBase.match(err("something entirely new happened"))).isNull()
  }

  @Test
  fun `every entry has both languages`() {
    for (e in ErrorKnowledgeBase.entries) {
      assertThat(e.en).isNotEmpty()
      assertThat(e.he).isNotEmpty()
      assertThat(e.hint(AnswerLanguage.HEBREW)).isEqualTo(e.he)
      assertThat(e.hint(AnswerLanguage.ENGLISH)).isEqualTo(e.en)
    }
  }

  @Test
  fun `language selection from locale`() {
    assertThat(AnswerLanguage.fromLocaleCode("he")).isEqualTo(AnswerLanguage.HEBREW)
    assertThat(AnswerLanguage.fromLocaleCode("iw")).isEqualTo(AnswerLanguage.HEBREW)
    assertThat(AnswerLanguage.fromLocaleCode("en")).isEqualTo(AnswerLanguage.ENGLISH)
    assertThat(AnswerLanguage.fromLocaleCode(null)).isEqualTo(AnswerLanguage.ENGLISH)
  }
}
