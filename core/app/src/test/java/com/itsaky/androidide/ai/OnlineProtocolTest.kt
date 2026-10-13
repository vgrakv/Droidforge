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
import com.google.gson.JsonParser
import org.junit.Test

class OnlineProtocolTest {

  private fun provider(id: String) = OnlineProviders.byId(id)!!

  @Test
  fun `openai-compatible request`() {
    val p = provider("groq")
    val spec = OnlineProtocol.buildRequest(p, "llama-x", "KEY", "be brief", "hi", 500)
    assertThat(spec.url).isEqualTo("https://api.groq.com/openai/v1/chat/completions")
    assertThat(spec.headers["authorization"]).isEqualTo("Bearer KEY")
    val body = JsonParser.parseString(spec.body).asJsonObject
    assertThat(body.get("model").asString).isEqualTo("llama-x")
    assertThat(body.get("max_tokens").asInt).isEqualTo(500)
    val msgs = body.getAsJsonArray("messages")
    assertThat(msgs[0].asJsonObject.get("role").asString).isEqualTo("system")
    assertThat(msgs[1].asJsonObject.get("content").asString).isEqualTo("hi")
  }

  @Test
  fun `openai uses max_completion_tokens and no temperature`() {
    val spec = OnlineProtocol.buildRequest(provider("openai"), "m", "K", "s", "u", 300)
    val body = JsonParser.parseString(spec.body).asJsonObject
    assertThat(body.has("max_completion_tokens")).isTrue()
    assertThat(body.has("max_tokens")).isFalse()
    assertThat(body.has("temperature")).isFalse()
  }

  @Test
  fun `anthropic request`() {
    val spec = OnlineProtocol.buildRequest(provider("anthropic"), "claude-x", "KEY", "sys", "usr", 700)
    assertThat(spec.url).isEqualTo("https://api.anthropic.com/v1/messages")
    assertThat(spec.headers["x-api-key"]).isEqualTo("KEY")
    assertThat(spec.headers["anthropic-version"]).isNotEmpty()
    assertThat(spec.headers.containsKey("authorization")).isFalse()
    val body = JsonParser.parseString(spec.body).asJsonObject
    assertThat(body.get("system").asString).isEqualTo("sys")
    assertThat(body.get("max_tokens").asInt).isEqualTo(700)
    assertThat(body.getAsJsonArray("messages")[0].asJsonObject.get("content").asString).isEqualTo("usr")
  }

  @Test
  fun `special characters survive json encoding`() {
    val tricky = "line1\n\"quoted\" \\ back עברית"
    val spec = OnlineProtocol.buildRequest(provider("groq"), "m", "K", "s", tricky, 10)
    val back = JsonParser.parseString(spec.body).asJsonObject
      .getAsJsonArray("messages")[1].asJsonObject.get("content").asString
    assertThat(back).isEqualTo(tricky)
  }

  @Test
  fun `custom endpoint without token sends no authorization header`() {
    val spec = OnlineProtocol.buildRequest(
      provider("custom"), "local-model", "", "s", "u", 10, baseUrl = "http://192.168.1.5:1234/v1/"
    )
    assertThat(spec.url).isEqualTo("http://192.168.1.5:1234/v1/chat/completions")
    assertThat(spec.headers.containsKey("authorization")).isFalse()
  }

  @Test
  fun `missing model or address is reported`() {
    for (call in listOf<() -> Unit>(
      { OnlineProtocol.buildRequest(provider("groq"), " ", "K", "s", "u", 1) },
      { OnlineProtocol.buildRequest(provider("custom"), "m", "", "s", "u", 1) },
    )) {
      try {
        call()
        throw AssertionError("expected OnlineException")
      } catch (e: OnlineException) {
        assertThat(e.message).isNotEmpty()
      }
    }
  }

  @Test
  fun `parses openai-compatible and anthropic responses`() {
    val openai = """{"choices":[{"message":{"role":"assistant","content":" Answer "}}]}"""
    assertThat(OnlineProtocol.parseResponse(provider("groq"), openai)).isEqualTo("Answer")

    val anthropic = """{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"world"}]}"""
    assertThat(OnlineProtocol.parseResponse(provider("anthropic"), anthropic)).isEqualTo("Hello world")
  }

  @Test
  fun `bad responses raise a friendly error`() {
    for (body in listOf("not json", """{"choices":[]}""", """{"choices":[{"message":{"content":null}}]}""")) {
      try {
        OnlineProtocol.parseResponse(provider("groq"), body)
        throw AssertionError("expected OnlineException for $body")
      } catch (e: OnlineException) {
        assertThat(e.message).isNotEmpty()
      }
    }
  }

  @Test
  fun `http errors are explained`() {
    val msg = OnlineProtocol.describeError(401, """{"error":{"message":"Invalid API key"}}""")
    assertThat(msg).contains("401")
    assertThat(msg).contains("token")
    assertThat(msg).contains("Invalid API key")
    assertThat(OnlineProtocol.describeError(429, null)).contains("quota")
    assertThat(OnlineProtocol.describeError(503, "<html>")).contains("temporary")
  }

  @Test
  fun `provider catalog is complete and consistent`() {
    val ids = OnlineProviders.all.map { it.id }
    assertThat(ids.toSet().size).isEqualTo(ids.size)
    assertThat(OnlineProviders.all.size).isAtLeast(8)
    for (p in OnlineProviders.all) {
      assertThat(p.name).isNotEmpty()
      assertThat(p.noteHe).isNotEmpty()
      assertThat(p.noteEn).isNotEmpty()
      if (p.customBaseUrl) {
        assertThat(p.tokenUrl).isNull()
      } else {
        assertThat(p.tokenUrl).startsWith("https://")
        assertThat(p.baseUrl).startsWith("https://")
        assertThat(p.defaultModel).isNotEmpty()
      }
    }
    assertThat(provider("anthropic").protocol).isEqualTo(ApiProtocol.ANTHROPIC)
  }
}
