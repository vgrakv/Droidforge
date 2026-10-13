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
import java.io.File
import java.util.Properties
import org.junit.Test

class ModelCatalogTest {

  @Test
  fun `modes map to the right models`() {
    assertThat(ModelCatalog.forMode(AiMode.BASIC)).containsExactly(ModelCatalog.EXPLAINER)
    assertThat(ModelCatalog.forMode(AiMode.HEBREW))
      .containsExactly(ModelCatalog.EXPLAINER, ModelCatalog.TRANSLATOR).inOrder()
    assertThat(ModelCatalog.forMode(AiMode.HEAVY)).containsExactly(ModelCatalog.FIXER)
    assertThat(ModelCatalog.forMode(AiMode.ONLINE)).isEmpty()
    assertThat(ModelCatalog.forMode(AiMode.NONE)).isEmpty()
  }

  @Test
  fun `catalog entries are public, consistent and not gated`() {
    for (m in ModelCatalog.all) {
      assertThat(m.url).startsWith("https://huggingface.co/litert-community/")
      assertThat(m.url).endsWith(".task")
      assertThat(m.minBytes).isLessThan(m.approxBytes)
      assertThat(m.assetPath).isEqualTo("ai-models/${m.fileName}")
      assertThat(ModelCatalog.byId(m.id)).isEqualTo(m)
      assertThat(m.originalFileName).endsWith(".task")
      assertThat(m.originalFileName).doesNotContain("/")
    }
    assertThat(ModelCatalog.all.map { it.id }.toSet().size).isEqualTo(ModelCatalog.all.size)
    assertThat(ModelCatalog.byId("nope")).isNull()
  }

  @Test
  fun `context windows match the files`() {
    assertThat(ModelCatalog.EXPLAINER.contextTokens).isEqualTo(1280)
    assertThat(ModelCatalog.EXPLAINER.url).contains("ekv1280")
    assertThat(ModelCatalog.TRANSLATOR.url).contains("ekv4096")
    assertThat(ModelCatalog.FIXER.url).contains("ekv4096")
    assertThat(ModelCatalog.FIXER.template).isEqualTo(ChatTemplate.PHI)
  }

  @Test
  fun `the user can choose another model of the same role`() {
    val choose = { role: ModelRole -> if (role == ModelRole.EXPLAINER) ModelCatalog.EXPLAINER_PLUS.id else null }
    assertThat(ModelCatalog.forMode(AiMode.HEBREW, choose))
      .containsExactly(ModelCatalog.EXPLAINER_PLUS, ModelCatalog.TRANSLATOR).inOrder()
    // a choice of the wrong role, or an unknown id, is ignored
    assertThat(ModelCatalog.forMode(AiMode.BASIC) { ModelCatalog.FIXER.id })
      .containsExactly(ModelCatalog.EXPLAINER)
    assertThat(ModelCatalog.forMode(AiMode.BASIC) { "nope" }).containsExactly(ModelCatalog.EXPLAINER)
  }

  @Test
  fun `alternatives are optional and listed after the default`() {
    for (role in ModelRole.values()) {
      val list = ModelCatalog.forRole(role)
      assertThat(list.first().isDefault).isTrue()
      assertThat(list.count { it.isDefault }).isEqualTo(1)
    }
    assertThat(ModelCatalog.all.size > ModelCatalog.defaults.size).isTrue()
    // different files must never share a name on the device
    assertThat(ModelCatalog.all.map { it.fileName }.toSet().size).isEqualTo(ModelCatalog.all.size)
    assertThat(ModelCatalog.all.map { it.originalFileName }.toSet().size).isEqualTo(ModelCatalog.all.size)
  }

  @Test
  fun `modes expose their abilities`() {
    assertThat(AiMode.HEAVY.canFix).isTrue()
    assertThat(AiMode.ONLINE.canFix).isTrue()
    assertThat(AiMode.BASIC.canFix).isFalse()
    assertThat(AiMode.HEBREW.canFix).isFalse()
    assertThat(AiMode.ONLINE.isLocal).isFalse()
    assertThat(AiMode.fromId(3)).isEqualTo(AiMode.HEAVY)
    assertThat(AiMode.fromId(99)).isEqualTo(AiMode.NONE)
  }

  @Test
  fun `chat templates`() {
    assertThat(ChatTemplate.CHATML.format("S", "U")).isEqualTo(
      "<|im_start|>system\nS<|im_end|>\n<|im_start|>user\nU<|im_end|>\n<|im_start|>assistant\n"
    )
    assertThat(ChatTemplate.PHI.format("S", "U")).isEqualTo("<|system|>S<|end|><|user|>U<|end|><|assistant|>")
  }

  /** ai-models.properties (used by Gradle to bundle models) must match the runtime catalog. */
  @Test
  fun `gradle bundle file is in sync with the catalog`() {
    val file = listOf(File("ai-models.properties"), File("core/app/ai-models.properties")).firstOrNull { it.isFile }
      ?: return // not run from the module directory
    val p = Properties().apply { file.reader().use { load(it) } }

    fun check(prefix: String, spec: ModelSpec) {
      assertThat(p.getProperty("$prefix.url")).isEqualTo(spec.url)
      assertThat(p.getProperty("$prefix.file")).isEqualTo(spec.fileName)
      assertThat(p.getProperty("$prefix.minBytes").toLong()).isEqualTo(spec.minBytes)
    }
    check("explainer", ModelCatalog.EXPLAINER)
    check("translator", ModelCatalog.TRANSLATOR)
    assertThat(p.getProperty("bundle.basic")).isEqualTo("explainer")
    assertThat(p.getProperty("bundle.hebrew")).isEqualTo("explainer,translator")
  }
}
