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

/** How the AI helper works. Chosen by the user on first run and changeable in the settings. */
enum class AiMode(val id: Int) {
  /** Nothing chosen yet. */
  NONE(0),

  /** 1: small local model that decodes the error. */
  BASIC(1),

  /** 2: the small model, plus a second local model that translates the answer to Hebrew. */
  HEBREW(2),

  /** 3: a heavier local model that explains (in Hebrew) and proposes code fixes. */
  HEAVY(3),

  /** 4: an online provider, using the user's own API token. */
  ONLINE(4);

  /** True when the mode runs models on the device (and therefore needs model files). */
  val isLocal: Boolean get() = this == BASIC || this == HEBREW || this == HEAVY

  /** True when the mode can propose automatic code fixes. */
  val canFix: Boolean get() = this == HEAVY || this == ONLINE

  companion object {
    fun fromId(id: Int): AiMode = values().firstOrNull { it.id == id } ?: NONE
  }
}
