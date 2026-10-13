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

package com.itsaky.androidide.preferences

import com.itsaky.androidide.ai.AiSetupActivity
import com.itsaky.androidide.resources.R
import kotlinx.parcelize.Parcelize

/** "AI helper" group in the main settings screen. */
@Parcelize
class AiPreferences(
  override val key: String = "idepref_ai",
  override val title: Int = R.string.title_ai_helper,
  override val children: List<IPreference> = mutableListOf()
) : IPreferenceGroup() {

  init {
    addPreference(aiSettings)
  }
}

val aiSettings =
  SimpleClickablePreference(
    key = "idepref_ai_settings",
    title = R.string.idepref_ai_title,
    summary = R.string.idepref_ai_summary
  ) {
    it.context.startActivity(AiSetupActivity.intent(it.context, settings = true))
    true
  }
