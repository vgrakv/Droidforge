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

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class ApiProtocol { OPENAI_COMPATIBLE, ANTHROPIC }

/** How well a provider's default model does a job. */
enum class Level { EXCELLENT, GOOD, BASIC, UNKNOWN }

/**
 * An online AI provider the user can use with their own API token.
 *
 * @property tokenUrl page where the user creates an API key (null for self-hosted endpoints)
 * @property usesCompletionTokens send `max_completion_tokens` instead of `max_tokens`
 */
data class OnlineProvider(
  val id: String,
  val name: String,
  val protocol: ApiProtocol,
  val baseUrl: String,
  val defaultModel: String,
  val tokenUrl: String?,
  /** What the default model can do. */
  val explain: Level,
  val hebrew: Level,
  val fix: Level,
  val hasFreeTier: Boolean,
  val noteHe: String,
  val noteEn: String,
  val usesCompletionTokens: Boolean = false,
  /** The user must type the endpoint (self-hosted / other compatible services). */
  val customBaseUrl: Boolean = false,
)

object OnlineProviders {

  val all: List<OnlineProvider> = listOf(
    OnlineProvider(
      "anthropic", "Anthropic Claude", ApiProtocol.ANTHROPIC, "https://api.anthropic.com",
      "claude-sonnet-5-5", "https://console.anthropic.com/settings/keys",
      Level.EXCELLENT, Level.EXCELLENT, Level.EXCELLENT, false,
      "בתשלום לפי שימוש. איכות גבוהה מאוד בקוד ובעברית.",
      "Pay as you go. Very high quality for code and Hebrew.",
    ),
    OnlineProvider(
      "openai", "OpenAI", ApiProtocol.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
      "gpt-4.1-mini", "https://platform.openai.com/api-keys",
      Level.EXCELLENT, Level.EXCELLENT, Level.EXCELLENT, false,
      "בתשלום לפי שימוש. עברית טובה מאוד.",
      "Pay as you go. Very good Hebrew.",
      usesCompletionTokens = true,
    ),
    OnlineProvider(
      "gemini", "Google Gemini", ApiProtocol.OPENAI_COMPATIBLE,
      "https://generativelanguage.googleapis.com/v1beta/openai",
      "gemini-2.5-flash", "https://aistudio.google.com/apikey",
      Level.EXCELLENT, Level.EXCELLENT, Level.GOOD, true,
      "יש שכבה חינמית (עם מגבלות קצב). עברית טובה מאוד.",
      "Has a free tier (rate limited). Very good Hebrew.",
    ),
    OnlineProvider(
      "groq", "Groq", ApiProtocol.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1",
      "llama-3.3-70b-versatile", "https://console.groq.com/keys",
      Level.GOOD, Level.GOOD, Level.GOOD, true,
      "שכבה חינמית ותשובות מהירות מאוד. עברית סבירה.",
      "Free tier and very fast answers. Decent Hebrew.",
    ),
    OnlineProvider(
      "openrouter", "OpenRouter", ApiProtocol.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1",
      "openrouter/auto", "https://openrouter.ai/settings/keys",
      Level.GOOD, Level.GOOD, Level.GOOD, true,
      "מפתח אחד לעשרות מודלים, כולל חינמיים. האיכות תלויה במודל שתבחר.",
      "One key for dozens of models, some free. Quality depends on the model you pick.",
    ),
    OnlineProvider(
      "mistral", "Mistral AI", ApiProtocol.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1",
      "mistral-small-latest", "https://console.mistral.ai/api-keys",
      Level.GOOD, Level.GOOD, Level.GOOD, true,
      "יש תוכנית ניסיון חינמית. עברית סבירה.",
      "Has a free experiment plan. Decent Hebrew.",
    ),
    OnlineProvider(
      "deepseek", "DeepSeek", ApiProtocol.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1",
      "deepseek-chat", "https://platform.deepseek.com/api_keys",
      Level.EXCELLENT, Level.GOOD, Level.EXCELLENT, false,
      "זול מאוד. חזק בקוד, עברית סבירה.",
      "Very cheap. Strong at code, decent Hebrew.",
    ),
    OnlineProvider(
      "together", "Together AI", ApiProtocol.OPENAI_COMPATIBLE, "https://api.together.xyz/v1",
      "meta-llama/Llama-3.3-70B-Instruct-Turbo", "https://api.together.ai/settings/api-keys",
      Level.GOOD, Level.GOOD, Level.GOOD, false,
      "בתשלום לפי שימוש, מבחר גדול של מודלים פתוחים.",
      "Pay as you go, many open models.",
    ),
    OnlineProvider(
      "cerebras", "Cerebras", ApiProtocol.OPENAI_COMPATIBLE, "https://api.cerebras.ai/v1",
      "llama-3.3-70b", "https://cloud.cerebras.ai/platform",
      Level.GOOD, Level.GOOD, Level.GOOD, true,
      "שכבה חינמית, מהיר מאוד. עברית סבירה.",
      "Free tier, very fast. Decent Hebrew.",
    ),
    OnlineProvider(
      "huggingface", "Hugging Face", ApiProtocol.OPENAI_COMPATIBLE, "https://router.huggingface.co/v1",
      "Qwen/Qwen2.5-Coder-32B-Instruct", "https://huggingface.co/settings/tokens",
      Level.GOOD, Level.BASIC, Level.GOOD, true,
      "קרדיט חינמי חודשי. חזק בקוד, עברית חלשה יותר.",
      "Free monthly credit. Strong at code, weaker Hebrew.",
    ),
    OnlineProvider(
      "custom", "Custom (OpenAI-compatible)", ApiProtocol.OPENAI_COMPATIBLE, "",
      "", null,
      Level.UNKNOWN, Level.UNKNOWN, Level.UNKNOWN, false,
      "כתובת משלך: LM Studio, Ollama או שרת תואם OpenAI. היכולות תלויות במודל.",
      "Your own endpoint: LM Studio, Ollama or any OpenAI-compatible server. Abilities depend on the model.",
      customBaseUrl = true,
    ),
  )

  fun byId(id: String?): OnlineProvider? = all.firstOrNull { it.id == id }
}

/** A ready-to-send HTTP POST. */
data class HttpSpec(val url: String, val headers: Map<String, String>, val body: String)

class OnlineException(message: String) : Exception(message)

/** Builds requests and parses responses for the two supported API styles. Pure, no networking. */
object OnlineProtocol {

  private const val ANTHROPIC_VERSION = "2023-06-01"

  fun buildRequest(
    provider: OnlineProvider,
    model: String,
    token: String,
    system: String,
    user: String,
    maxTokens: Int,
    baseUrl: String = provider.baseUrl,
  ): HttpSpec {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) throw OnlineException("No endpoint address was set.")
    if (model.isBlank()) throw OnlineException("No model name was set.")

    return when (provider.protocol) {
      ApiProtocol.ANTHROPIC -> {
        val body = JsonObject().apply {
          addProperty("model", model)
          addProperty("max_tokens", maxTokens)
          addProperty("system", system)
          add("messages", JsonArray().apply { add(message("user", user)) })
        }
        HttpSpec(
          "$base/v1/messages",
          mapOf(
            "x-api-key" to token,
            "anthropic-version" to ANTHROPIC_VERSION,
            "content-type" to "application/json",
          ),
          body.toString(),
        )
      }
      ApiProtocol.OPENAI_COMPATIBLE -> {
        val body = JsonObject().apply {
          addProperty("model", model)
          add("messages", JsonArray().apply {
            add(message("system", system))
            add(message("user", user))
          })
          addProperty(if (provider.usesCompletionTokens) "max_completion_tokens" else "max_tokens", maxTokens)
          if (!provider.usesCompletionTokens) addProperty("temperature", 0.2)
        }
        val headers = mutableMapOf("content-type" to "application/json")
        if (token.isNotBlank()) headers["authorization"] = "Bearer $token"
        HttpSpec("$base/chat/completions", headers, body.toString())
      }
    }
  }

  private fun message(role: String, content: String) = JsonObject().apply {
    addProperty("role", role)
    addProperty("content", content)
  }

  /** Extracts the answer text from a successful response body. */
  fun parseResponse(provider: OnlineProvider, body: String): String {
    val root = try {
      JsonParser.parseString(body).asJsonObject
    } catch (e: Exception) {
      throw OnlineException("The server returned an unexpected answer.")
    }
    return try {
      when (provider.protocol) {
        ApiProtocol.ANTHROPIC -> root.getAsJsonArray("content")
          .map { it.asJsonObject }
          .filter { it.get("type")?.asString == "text" }
          .joinToString("") { it.get("text").asString }
        ApiProtocol.OPENAI_COMPATIBLE -> {
          val content = root.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").get("content")
          if (content == null || content.isJsonNull) "" else content.asString
        }
      }.trim().ifEmpty { throw OnlineException("The model returned an empty answer.") }
    } catch (e: OnlineException) {
      throw e
    } catch (e: Exception) {
      throw OnlineException("The server returned an unexpected answer.")
    }
  }

  /** Turns an HTTP error into a message the user can act on. */
  fun describeError(code: Int, body: String?): String {
    val detail = try {
      val root = JsonParser.parseString(body ?: "").asJsonObject
      val err = root.get("error")
      when {
        err == null -> null
        err.isJsonObject -> err.asJsonObject.get("message")?.asString
        else -> err.asString
      }
    } catch (e: Exception) {
      null
    }?.take(200)

    val reason = when (code) {
      400 -> "The request was rejected (check the model name)."
      401, 403 -> "The token was rejected. Check that it is correct and still active."
      404 -> "The address or model was not found."
      429 -> "Rate limit or quota reached. Try again later."
      in 500..599 -> "The provider has a temporary problem."
      else -> "Unexpected error."
    }
    return "HTTP $code: $reason" + if (detail != null) " ($detail)" else ""
  }
}
