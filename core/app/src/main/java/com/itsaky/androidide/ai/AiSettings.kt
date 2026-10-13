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

import android.content.Context

/** Persisted settings of the AI helper. */
object AiSettings {
  private const val FILE = "ai_helper"
  private const val KEY_MODE = "mode"
  private const val KEY_SETUP_DONE = "setup_done"
  private const val KEY_TOUR_DONE = "tour_done"
  private const val KEY_WIFI_ONLY = "wifi_only"
  private const val KEY_PROVIDER = "online_provider"
  private const val KEY_ROLE_PREFIX = "local_model_"
  private const val KEY_MODEL_PREFIX = "online_model_"
  private const val KEY_BASE_URL_PREFIX = "online_base_url_"

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  fun mode(context: Context): AiMode = AiMode.fromId(prefs(context).getInt(KEY_MODE, 0))

  fun setMode(context: Context, mode: AiMode) {
    prefs(context).edit().putInt(KEY_MODE, mode.id).apply()
  }

  /** Whether the user already went through the first-run chooser. */
  fun isSetupDone(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_DONE, false)

  fun setSetupDone(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_SETUP_DONE, value).apply()
  }

  fun isTourDone(context: Context): Boolean = prefs(context).getBoolean(KEY_TOUR_DONE, false)

  fun setTourDone(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_TOUR_DONE, value).apply()
  }

  /** Models are big, so by default they are only downloaded on an unmetered network (Wi-Fi). */
  fun isWifiOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_WIFI_ONLY, true)

  fun setWifiOnly(context: Context, value: Boolean) {
    prefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply()
  }

  // ---- which local model fills a role ----------------------------------------------------

  /** Id of the model the user chose for [role], or null for the default one. */
  fun modelChoice(context: Context, role: ModelRole): String? =
    prefs(context).getString(KEY_ROLE_PREFIX + role.name, null)

  /** Chooses [spec] for its role; choosing the default clears the choice. */
  fun setModelChoice(context: Context, spec: ModelSpec) {
    val e = prefs(context).edit()
    if (spec.isDefault) e.remove(KEY_ROLE_PREFIX + spec.role.name)
    else e.putString(KEY_ROLE_PREFIX + spec.role.name, spec.id)
    e.apply()
  }

  // ---- online mode -------------------------------------------------------------------------

  fun providerId(context: Context): String? = prefs(context).getString(KEY_PROVIDER, null)

  fun setProviderId(context: Context, id: String) {
    prefs(context).edit().putString(KEY_PROVIDER, id).apply()
  }

  fun provider(context: Context): OnlineProvider? = OnlineProviders.byId(providerId(context))

  /** The model name chosen for [provider] (falls back to the provider's default). */
  fun onlineModel(context: Context, provider: OnlineProvider): String =
    prefs(context).getString(KEY_MODEL_PREFIX + provider.id, null)?.takeIf { it.isNotBlank() }
      ?: provider.defaultModel

  fun setOnlineModel(context: Context, provider: OnlineProvider, model: String) {
    prefs(context).edit().putString(KEY_MODEL_PREFIX + provider.id, model.trim()).apply()
  }

  fun baseUrl(context: Context, provider: OnlineProvider): String =
    prefs(context).getString(KEY_BASE_URL_PREFIX + provider.id, null)?.takeIf { it.isNotBlank() }
      ?: provider.baseUrl

  fun setBaseUrl(context: Context, provider: OnlineProvider, url: String) {
    prefs(context).edit().putString(KEY_BASE_URL_PREFIX + provider.id, url.trim()).apply()
  }

  fun tokenName(provider: OnlineProvider) = "token_${provider.id}"

  fun token(context: Context, provider: OnlineProvider): String? =
    SecretStore.get(context, tokenName(provider))

  fun setToken(context: Context, provider: OnlineProvider, token: String): Boolean =
    SecretStore.put(context, tokenName(provider), token.trim())

  fun clearToken(context: Context, provider: OnlineProvider) {
    SecretStore.remove(context, tokenName(provider))
  }

  /** True when online mode is fully configured and can be used right now. */
  fun isOnlineReady(context: Context): Boolean {
    val p = provider(context) ?: return false
    if (baseUrl(context, p).isBlank() || onlineModel(context, p).isBlank()) return false
    // Self-hosted endpoints may not need a token.
    return p.customBaseUrl || !token(context, p).isNullOrBlank()
  }
}
