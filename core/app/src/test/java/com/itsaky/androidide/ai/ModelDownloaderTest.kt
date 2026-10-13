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
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Before
import org.junit.Test

class ModelDownloaderTest {

  private lateinit var server: HttpServer
  private lateinit var dir: File
  private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
  private val requests = AtomicInteger()

  /** If > 0 the first full (non-range) response is cut after this many bytes. */
  @Volatile private var breakAfter = 0
  @Volatile private var lastRange: String? = null

  private fun spec(min: Long = 1000) = ModelSpec(
    id = "t",
    displayName = "Test",
    role = ModelRole.EXPLAINER,
    url = "http://127.0.0.1:${server.address.port}/start",
    fileName = "model.task",
    minBytes = min,
    approxBytes = 300_000,
    contextTokens = 1280,
  )

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("dl").toFile()
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/start") { ex ->
      ex.responseHeaders.add("Location", "/file")
      ex.sendResponseHeaders(302, -1)
      ex.close()
    }
    server.createContext("/file") { ex ->
      requests.incrementAndGet()
      val range = ex.requestHeaders.getFirst("Range")
      lastRange = range
      if (range != null) {
        val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
        if (from >= payload.size) {
          ex.sendResponseHeaders(416, -1)
        } else {
          val len = payload.size - from
          ex.sendResponseHeaders(206, len.toLong())
          ex.responseBody.write(payload, from, len)
        }
      } else {
        ex.sendResponseHeaders(200, payload.size.toLong())
        if (breakAfter > 0) {
          ex.responseBody.write(payload, 0, breakAfter)
          ex.responseBody.flush()
          breakAfter = 0
          ex.close() // connection closed before Content-Length bytes were sent
          return@createContext
        }
        ex.responseBody.write(payload)
      }
      ex.close()
    }
    server.start()
  }

  @After
  fun tearDown() {
    server.stop(0)
    dir.deleteRecursively()
  }

  @Test
  fun `downloads through a redirect and reports progress`() {
    var last = 0L
    val f = ModelDownloader().download(spec(), dir, { done, _ -> last = done })
    assertThat(f.readBytes()).isEqualTo(payload)
    assertThat(last).isEqualTo(payload.size.toLong())
    assertThat(File(dir, "model.task.part").exists()).isFalse()
  }

  @Test
  fun `resumes an interrupted download with a Range request`() {
    breakAfter = 100_000
    try {
      ModelDownloader().download(spec(), dir)
      throw AssertionError("expected an IOException")
    } catch (e: IOException) {
      // expected: incomplete download
    }
    val part = File(dir, "model.task.part")
    assertThat(part.length()).isEqualTo(100_000L)
    assertThat(File(dir, "model.task").exists()).isFalse()

    val f = ModelDownloader().download(spec(), dir)
    assertThat(lastRange).isEqualTo("bytes=100000-")
    assertThat(f.readBytes()).isEqualTo(payload)
  }

  @Test
  fun `an existing complete model is not downloaded again`() {
    ModelDownloader().download(spec(), dir)
    val before = requests.get()
    ModelDownloader().download(spec(), dir)
    assertThat(requests.get()).isEqualTo(before)
  }

  @Test
  fun `a complete part file is accepted after a 416`() {
    File(dir, "model.task.part").writeBytes(payload)
    val f = ModelDownloader().download(spec(), dir)
    assertThat(f.readBytes()).isEqualTo(payload)
  }

  @Test
  fun `a too small file is rejected and removed`() {
    try {
      ModelDownloader().download(spec(min = 10_000_000), dir)
      throw AssertionError("expected an IOException")
    } catch (e: IOException) {
      assertThat(e.message).contains("too small")
    }
    assertThat(File(dir, "model.task").exists()).isFalse()
    assertThat(File(dir, "model.task.part").exists()).isFalse()
  }

  @Test
  fun `cancellation stops the download and keeps the part file`() {
    var cancelled = false
    try {
      ModelDownloader().download(spec(), dir, null) { cancelled = true; true }
      throw AssertionError("expected CancelledException")
    } catch (e: ModelDownloader.CancelledException) {
      // expected
    }
    assertThat(cancelled).isTrue()
    assertThat(File(dir, "model.task").exists()).isFalse()
  }
}
