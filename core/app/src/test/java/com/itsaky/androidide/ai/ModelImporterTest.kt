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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Before
import org.junit.Test

class ModelImporterTest {

  private lateinit var dir: File
  private val mb = 1024L * 1024

  /** A small fake model: same role as the real one, but a tiny minimum size. */
  private val spec = ModelCatalog.EXPLAINER.copy(minBytes = 1000, approxBytes = 100_000)

  private fun zipBytes(size: Int) = ByteArray(size) { i ->
    when (i) { 0 -> 0x50; 1 -> 0x4B; 2 -> 0x03; 3 -> 0x04; else -> (i % 251) }.toByte()
  }

  private fun import(
    data: ByteArray,
    declared: Long = data.size.toLong(),
    free: Long = -1,
    cancelAfterReads: Int = Int.MAX_VALUE,
    s: ModelSpec = spec,
    progress: ((Long, Long) -> Unit)? = null,
  ): File {
    var reads = 0
    val input = object : InputStream() {
      private val inner = ByteArrayInputStream(data)
      override fun read(): Int = inner.read()
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        reads++
        return inner.read(b, off, minOf(len, 4096))
      }
    }
    return ModelImporter.import(s, input, declared, dir, free, progress) { reads >= cancelAfterReads }
  }

  private fun importError(block: () -> Unit): ImportError {
    try {
      block()
    } catch (e: ImportException) {
      return e.error
    }
    throw AssertionError("expected an ImportException")
  }

  @Before
  fun setUp() {
    dir = Files.createTempDirectory("imp").toFile()
  }

  @After
  fun tearDown() {
    dir.deleteRecursively()
  }

  // ---- identify ----------------------------------------------------------------------------

  @Test
  fun `exact hugging face file names are recognised`() {
    for (m in ModelCatalog.all) {
      assertThat(ModelImporter.identify(m.originalFileName, m.approxBytes)).containsExactly(m)
      assertThat(ModelImporter.identify(m.fileName, -1)).containsExactly(m)
    }
  }

  @Test
  fun `browser duplicate suffix and case are ignored`() {
    val m = ModelCatalog.TRANSLATOR
    val name = m.originalFileName.replace(".task", " (1).task").uppercase()
    assertThat(ModelImporter.identify(name, m.approxBytes)).containsExactly(m)
  }

  @Test
  fun `keywords with a plausible size identify a renamed file`() {
    assertThat(ModelImporter.identify("my-qwen-0.5b-model.task", ModelCatalog.EXPLAINER.approxBytes))
      .containsExactly(ModelCatalog.EXPLAINER)
    assertThat(ModelImporter.identify("phi.task", ModelCatalog.FIXER.approxBytes))
      .containsExactly(ModelCatalog.FIXER)
    // keyword says 1.5B but the size is that of a 0.5B model: not trusted
    assertThat(ModelImporter.identify("qwen-1.5b.task", ModelCatalog.EXPLAINER.approxBytes))
      .doesNotContain(ModelCatalog.TRANSLATOR)
  }

  @Test
  fun `an unknown name falls back to the size, or to nothing`() {
    val bySize = ModelImporter.identify("download.task", ModelCatalog.FIXER.approxBytes)
    assertThat(bySize).contains(ModelCatalog.FIXER)
    assertThat(ModelImporter.identify("download.task", 5_000)).isEmpty()
    assertThat(ModelImporter.identify(null, -1)).isEmpty()
  }

  // ---- looksLikeTask -----------------------------------------------------------------------

  @Test
  fun `task files are zip archives`() {
    assertThat(ModelImporter.looksLikeTask(zipBytes(10))).isTrue()
    assertThat(ModelImporter.looksLikeTask("<html>".toByteArray())).isFalse()
    assertThat(ModelImporter.looksLikeTask(byteArrayOf(0x50, 0x4B))).isFalse()
  }

  // ---- import ------------------------------------------------------------------------------

  @Test
  fun `a valid file is copied under its final name`() {
    val data = zipBytes(20_000)
    var last = 0L
    val f = import(data, progress = { done, _ -> last = done })
    assertThat(f.name).isEqualTo(spec.fileName)
    assertThat(f.readBytes()).isEqualTo(data)
    assertThat(last).isEqualTo(data.size.toLong())
    assertThat(File(dir, spec.fileName + ".part").exists()).isFalse()
  }

  @Test
  fun `an existing model is replaced`() {
    File(dir, spec.fileName).writeBytes(ByteArray(5000))
    val data = zipBytes(8000)
    assertThat(import(data).readBytes()).isEqualTo(data)
  }

  @Test
  fun `an html page saved by mistake is rejected and leaves nothing`() {
    val html = ("<html>" + "x".repeat(20_000) + "</html>").toByteArray()
    assertThat(importError { import(html) }).isEqualTo(ImportError.NOT_A_MODEL)
    assertThat(dir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun `empty and too small files are rejected`() {
    assertThat(importError { import(ByteArray(0), declared = 0) }).isEqualTo(ImportError.TOO_SMALL)
    assertThat(importError { import(zipBytes(500), declared = 500) }).isEqualTo(ImportError.TOO_SMALL)
    // size unknown: caught after copying
    assertThat(importError { import(zipBytes(500), declared = -1) }).isEqualTo(ImportError.TOO_SMALL)
    assertThat(dir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun `not enough space is detected before copying`() {
    val data = zipBytes(20_000)
    assertThat(importError { import(data, free = 10_000) }).isEqualTo(ImportError.NO_SPACE)
    assertThat(dir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun `cancelling removes the partial file`() {
    val data = zipBytes(200_000)
    assertThat(importError { import(data, cancelAfterReads = 3) }).isEqualTo(ImportError.CANCELLED)
    assertThat(dir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun `a provider that returns one byte per read still works`() {
    val data = zipBytes(3_000)
    val trickle = object : InputStream() {
      private val inner = ByteArrayInputStream(data)
      override fun read(): Int = inner.read()
      override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 1))
    }
    val f = ModelImporter.import(spec, trickle, data.size.toLong(), dir)
    assertThat(f.readBytes()).isEqualTo(data)
  }

  @Test
  fun `a truncated copy is reported`() {
    val data = zipBytes(20_000)
    assertThat(importError { import(data, declared = 30_000) }).isEqualTo(ImportError.IO)
    assertThat(File(dir, spec.fileName).exists()).isFalse()
  }

  @Test
  fun `read errors are reported and clean up`() {
    val failing = object : InputStream() {
      private var n = 0
      override fun read(): Int = throw IOException("boom")
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (n++ == 0) { b[0] = 0x50; b[1] = 0x4B; b[2] = 0x03; b[3] = 0x04; return 4 }
        throw IOException("boom")
      }
    }
    assertThat(importError { ModelImporter.import(spec, failing, -1, dir) }).isEqualTo(ImportError.IO)
    assertThat(dir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun `real catalog sizes are plausible for themselves`() {
    for (m in ModelCatalog.all) {
      assertThat(ModelImporter.plausibleSize(m, m.approxBytes)).isTrue()
      assertThat(ModelImporter.plausibleSize(m, mb)).isFalse()
    }
  }
}
