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
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores secrets (API tokens) encrypted with a key that lives in the Android Keystore and never
 * leaves the secure hardware/OS. Tokens are never written in plain text.
 */
object SecretStore {

  private const val PROVIDER = "AndroidKeyStore"
  private const val ALIAS = "droidforge_ai_secrets"
  private const val FILE = "ai_secrets"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"

  private fun key(): SecretKey {
    val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
    (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

    val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
    gen.init(
      KeyGenParameterSpec.Builder(
        ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return gen.generateKey()
  }

  private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** @return false if the device could not store the secret securely. */
  fun put(context: Context, name: String, value: String): Boolean {
    return try {
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(Cipher.ENCRYPT_MODE, key())
      val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
      val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
        Base64.encodeToString(encrypted, Base64.NO_WRAP)
      prefs(context).edit().putString(name, packed).apply()
      true
    } catch (e: Exception) {
      false
    }
  }

  fun get(context: Context, name: String): String? {
    val packed = prefs(context).getString(name, null) ?: return null
    return try {
      val (iv, data) = packed.split(':', limit = 2)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      cipher.init(
        Cipher.DECRYPT_MODE, key(),
        GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
      )
      String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
      null
    }
  }

  fun remove(context: Context, name: String) {
    prefs(context).edit().remove(name).apply()
  }

  fun has(context: Context, name: String): Boolean = prefs(context).contains(name)
}
