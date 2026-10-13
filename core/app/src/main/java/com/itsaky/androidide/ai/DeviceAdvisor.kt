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

/** Facts about the device that decide which AI modes make sense. */
data class DeviceProfile(
  val totalRamBytes: Long,
  val freeStorageBytes: Long,
  val abis: List<String>,
  val sdkInt: Int,
  val cpuCores: Int,
  val hasInternet: Boolean,
)

enum class Verdict { RECOMMENDED, NOT_RECOMMENDED, IMPOSSIBLE }

/** A verdict with a human-readable reason in both languages. */
data class Assessment(val verdict: Verdict, val reasonHe: String, val reasonEn: String) {
  fun reason(lang: AnswerLanguage) = if (lang == AnswerLanguage.HEBREW) reasonHe else reasonEn
}

/**
 * Estimates, for each model and for each mode, whether installing it is a good idea
 * on a given device ("recommended" / "not recommended" / "impossible").
 */
object DeviceAdvisor {

  private const val GB = 1024.0 * 1024 * 1024

  private fun gb(bytes: Long) = bytes / GB
  private fun fmt(v: Double) = String.format(java.util.Locale.US, "%.1f", v)

  fun assess(spec: ModelSpec, device: DeviceProfile): Assessment =
    assessAll(listOf(spec), device)

  /** The verdict for a set of models that must all be installed (e.g. the HEBREW mode). */
  fun assessAll(specs: List<ModelSpec>, device: DeviceProfile): Assessment {
    if (specs.isEmpty()) return Assessment(Verdict.RECOMMENDED, "אין צורך במודל", "No model needed")

    if (device.abis.none { it == "arm64-v8a" }) {
      return Assessment(
        Verdict.IMPOSSIBLE,
        "המעבד של המכשיר (${device.abis.firstOrNull() ?: "?"}) לא נתמך. נדרש מעבד 64-ביט מסוג ARM.",
        "This CPU (${device.abis.firstOrNull() ?: "?"}) is not supported. A 64-bit ARM CPU is required.",
      )
    }

    val totalSize = specs.sumOf { it.approxBytes }
    val needSpace = (totalSize * 1.2).toLong()
    if (device.freeStorageBytes < needSpace) {
      return Assessment(
        Verdict.IMPOSSIBLE,
        "אין מספיק מקום פנוי: נדרשים כ-${fmt(gb(needSpace))}GB ופנויים ${fmt(gb(device.freeStorageBytes))}GB.",
        "Not enough free space: about ${fmt(gb(needSpace))}GB needed, ${fmt(gb(device.freeStorageBytes))}GB free.",
      )
    }

    // Models are loaded one at a time, so the biggest requirement decides.
    val minRam = specs.maxOf { it.minRamGb }
    val recRam = specs.maxOf { it.recommendedRamGb }
    val ram = gb(device.totalRamBytes)
    // Reported RAM is a bit below the marketed size (a "8GB" phone reports ~7.4GB).
    val ramRounded = Math.ceil(ram).toInt()

    return when {
      ramRounded < minRam -> Assessment(
        Verdict.IMPOSSIBLE,
        "זיכרון RAM של ${fmt(ram)}GB נמוך מדי (נדרשים לפחות ${minRam}GB).",
        "${fmt(ram)}GB of RAM is too low (at least ${minRam}GB needed).",
      )
      ramRounded < recRam -> Assessment(
        Verdict.NOT_RECOMMENDED,
        "יעבוד, אבל איטי וייתכנו קריסות בזמן בנייה: יש ${fmt(ram)}GB RAM והמומלץ ${recRam}GB.",
        "It can run, but slowly and it may crash during builds: ${fmt(ram)}GB RAM, ${recRam}GB recommended.",
      )
      else -> Assessment(
        Verdict.RECOMMENDED,
        "המכשיר מתאים: ${fmt(ram)}GB RAM, ${fmt(gb(device.freeStorageBytes))}GB פנויים.",
        "Your device fits: ${fmt(ram)}GB RAM, ${fmt(gb(device.freeStorageBytes))}GB free.",
      )
    }
  }

  fun assessMode(mode: AiMode, device: DeviceProfile): Assessment = when (mode) {
    AiMode.NONE -> Assessment(Verdict.RECOMMENDED, "הסברים מוכנים בלבד", "Built-in hints only")
    AiMode.ONLINE ->
      if (device.hasInternet) {
        Assessment(Verdict.RECOMMENDED, "דורש חיבור לאינטרנט וטוקן. לא מעמיס על המכשיר.", "Needs internet and a token. Light on the device.")
      } else {
        Assessment(Verdict.NOT_RECOMMENDED, "אין כרגע חיבור לאינטרנט. המצב עובד רק כשיש חיבור.", "No internet right now. This mode only works while online.")
      }
    else -> assessAll(ModelCatalog.forMode(mode), device)
  }
}
