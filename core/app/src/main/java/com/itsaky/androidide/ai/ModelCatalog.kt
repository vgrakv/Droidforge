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

/** What a model is used for. */
enum class ModelRole { EXPLAINER, TRANSLATOR, FIXER }

/** Prompt format a model was trained with. */
enum class ChatTemplate {
  /** Qwen family. */
  CHATML,

  /** Phi family. */
  PHI;

  fun format(system: String, user: String): String = when (this) {
    CHATML ->
      "<|im_start|>system\n$system<|im_end|>\n" +
        "<|im_start|>user\n$user<|im_end|>\n" +
        "<|im_start|>assistant\n"
    PHI -> "<|system|>$system<|end|><|user|>$user<|end|><|assistant|>"
  }
}

/**
 * A downloadable on-device model (MediaPipe `.task` bundle). All entries are public,
 * ungated downloads: no tokens needed.
 */
data class ModelSpec(
  val id: String,
  val displayName: String,
  val role: ModelRole,
  val url: String,
  val fileName: String,
  /** Anything smaller than this is certainly not the real model (e.g. an HTML error page). */
  val minBytes: Long,
  /** Approximate real size, used for disk-space estimates and the UI. */
  val approxBytes: Long,
  /** Total context window (prompt + answer) baked into the model file. */
  val contextTokens: Int,
  val template: ChatTemplate = ChatTemplate.CHATML,
  /** Below this much total device RAM the model cannot run reliably. */
  val minRamGb: Int = 4,
  /** From this much total RAM it runs comfortably next to the IDE. */
  val recommendedRamGb: Int = 6,
  /** The model a mode uses unless the user picked another one of the same [role]. */
  val isDefault: Boolean = true,
  /** True when [approxBytes] is an estimate (the real size was not checked). */
  val sizeEstimated: Boolean = false,
  /** One line about what makes this model different from the default of its role. */
  val noteHe: String = "",
  val noteEn: String = "",
) {
  /** Path of the file inside the APK when the APK was built with models bundled. */
  val assetPath: String get() = "$ASSET_DIR/$fileName"

  /** Name of the file as it is called on Hugging Face (what a browser saves when downloading it). */
  val originalFileName: String get() = url.substringBefore('?').substringAfterLast('/')

  companion object {
    const val ASSET_DIR = "ai-models"
  }
}

object ModelCatalog {

  private const val HF = "https://huggingface.co/litert-community"
  private const val MB = 1024L * 1024
  private const val GB = 1024L * MB

  /** Qwen2.5-0.5B-Instruct (Apache-2.0), int8. Decodes errors in English. */
  val EXPLAINER = ModelSpec(
    id = "qwen2.5-0.5b-q8",
    displayName = "Qwen2.5 0.5B",
    role = ModelRole.EXPLAINER,
    url = "$HF/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
    fileName = "Qwen2.5-0.5B-Instruct_q8_ekv1280.task",
    minBytes = 250 * MB,
    approxBytes = 546_660_344L, // size on Hugging Face, checked 2026-10-13
    contextTokens = 1280,
    template = ChatTemplate.CHATML,
    minRamGb = 3,
    recommendedRamGb = 4,
  )

  /** Qwen2.5-1.5B-Instruct (Apache-2.0), int8, 4K context. Translates the answer to Hebrew. */
  val TRANSLATOR = ModelSpec(
    id = "qwen2.5-1.5b-q8-4k",
    displayName = "Qwen2.5 1.5B",
    role = ModelRole.TRANSLATOR,
    url = "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
    fileName = "Qwen2.5-1.5B-Instruct_q8_ekv4096.task",
    minBytes = 900 * MB,
    approxBytes = 1_598_556_720L, // size on Hugging Face, checked 2026-10-13
    contextTokens = 4096,
    template = ChatTemplate.CHATML,
    minRamGb = 6,
    recommendedRamGb = 8,
  )

  /** Phi-4-mini-instruct (MIT), int8, 4K context. Explains in Hebrew and proposes code fixes. */
  val FIXER = ModelSpec(
    id = "phi-4-mini-q8-4k",
    displayName = "Phi-4 mini",
    role = ModelRole.FIXER,
    url = "$HF/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task",
    fileName = "Phi-4-mini-instruct_q8_ekv4096.task",
    minBytes = 2500 * MB,
    approxBytes = 3_910_050_199L, // size on Hugging Face, checked 2026-10-13
    contextTokens = 4096,
    template = ChatTemplate.PHI,
    minRamGb = 8,
    recommendedRamGb = 12,
  )

  // ---- optional alternatives (same roles, other trade-offs) ---------------------------------
  // File names are the ones listed in the Hugging Face repositories; sizes are estimates.

  /** Qwen2.5-1.5B int8 with a short (1280) context: more accurate decoder than the 0.5B one. */
  val EXPLAINER_PLUS = ModelSpec(
    id = "qwen2.5-1.5b-q8-1k",
    displayName = "Qwen2.5 1.5B (short context)",
    role = ModelRole.EXPLAINER,
    url = "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
    fileName = "Qwen2.5-1.5B-Instruct_q8_ekv1280.task",
    minBytes = 900 * MB,
    approxBytes = 1_550L * MB,
    contextTokens = 1280,
    template = ChatTemplate.CHATML,
    minRamGb = 5,
    recommendedRamGb = 6,
    isDefault = false,
    sizeEstimated = true,
    noteHe = "מפענח מדויק יותר מהמודל הקטן, אך כבד ממנו",
    noteEn = "A more accurate decoder than the small model, but heavier",
  )

  /** Qwen2.5-1.5B in full 32-bit precision: the best Hebrew translation here, but very big. */
  val TRANSLATOR_PRECISE = ModelSpec(
    id = "qwen2.5-1.5b-f32-4k",
    displayName = "Qwen2.5 1.5B (full precision)",
    role = ModelRole.TRANSLATOR,
    url = "$HF/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_f32_ekv4096.task",
    fileName = "Qwen2.5-1.5B-Instruct_f32_ekv4096.task",
    minBytes = 3000 * MB,
    approxBytes = 6_200L * MB,
    contextTokens = 4096,
    template = ChatTemplate.CHATML,
    minRamGb = 10,
    recommendedRamGb = 16,
    isDefault = false,
    sizeEstimated = true,
    noteHe = "תרגום מדויק יותר, אך כ-4 פעמים גדול יותר",
    noteEn = "More precise translation, but about 4 times bigger",
  )

  /** The default model of each role (what the modes use). */
  val defaults = listOf(EXPLAINER, TRANSLATOR, FIXER)

  /** Every model the app knows. */
  val all = defaults + listOf(EXPLAINER_PLUS, TRANSLATOR_PRECISE)

  /** Models that can fill [role] (default first). */
  fun forRole(role: ModelRole): List<ModelSpec> = all.filter { it.role == role }.sortedBy { !it.isDefault }

  fun byId(id: String?): ModelSpec? = all.firstOrNull { it.id == id }

  /** The model files a mode needs, in download order. */
  fun forMode(mode: AiMode, choice: (ModelRole) -> String? = { null }): List<ModelSpec> {
    val base = when (mode) {
      AiMode.BASIC -> listOf(EXPLAINER)
      AiMode.HEBREW -> listOf(EXPLAINER, TRANSLATOR)
      AiMode.HEAVY -> listOf(FIXER)
      AiMode.NONE, AiMode.ONLINE -> emptyList()
    }
    // The user may have chosen another model for a role; it must really be of that role.
    return base.map { def -> byId(choice(def.role))?.takeIf { it.role == def.role } ?: def }
  }
}
