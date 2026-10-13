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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends one prompt to the online provider chosen in the settings and returns the answer. */
object OnlineClient {

  private const val TIMEOUT_MS = 90_000

  /** @throws OnlineException with a message that can be shown to the user. */
  suspend fun complete(
    context: Context,
    system: String,
    user: String,
    maxTokens: Int,
  ): String = withContext(Dispatchers.IO) {
    val provider = AiSettings.provider(context) ?: throw OnlineException("No online provider was chosen.")
    val token = AiSettings.token(context, provider).orEmpty()
    if (token.isBlank() && !provider.customBaseUrl) {
      throw OnlineException("No token was saved for ${provider.name}.")
    }
    val spec = OnlineProtocol.buildRequest(
      provider = provider,
      model = AiSettings.onlineModel(context, provider),
      token = token,
      system = system,
      user = user,
      maxTokens = maxTokens,
      baseUrl = AiSettings.baseUrl(context, provider),
    )
    OnlineProtocol.parseResponse(provider, post(spec))
  }

  private fun post(spec: HttpSpec): String {
    val conn = URL(spec.url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = TIMEOUT_MS
      conn.readTimeout = TIMEOUT_MS
      conn.doOutput = true
      spec.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
      conn.outputStream.use { it.write(spec.body.toByteArray(Charsets.UTF_8)) }

      val code = conn.responseCode
      val stream = if (code in 200..299) conn.inputStream else conn.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw OnlineException(OnlineProtocol.describeError(code, text))
      return text
    } catch (e: OnlineException) {
      throw e
    } catch (e: IOException) {
      throw OnlineException("Cannot reach the server: ${e.message ?: e.javaClass.simpleName}")
    } finally {
      conn.disconnect()
    }
  }
}
