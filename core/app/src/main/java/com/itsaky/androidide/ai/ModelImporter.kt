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

import java.io.File
import java.io.IOException
import java.io.InputStream

enum class ImportError { NOT_A_MODEL, TOO_SMALL, NO_SPACE, CANCELLED, IO }

class ImportException(val error: ImportError, message: String) : IOException(message)

/**
 * Imports a model file the user downloaded themselves (e.g. with a browser) instead of letting the
 * app download it. Pure Kotlin (no Android classes), so it can be unit-tested.
 */
object ModelImporter {

  /** A MediaPipe `.task` bundle is a ZIP archive. */
  private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

  /** Free space that must remain after the copy. */
  private const val SPACE_MARGIN = 64L * 1024 * 1024

  private val DUPLICATE_SUFFIX = Regex("""\s*\(\d+\)$""")

  private fun stem(name: String): String =
    name.trim().substringBeforeLast('.', name.trim()).replace(DUPLICATE_SUFFIX, "").lowercase()

  /** True when a file of [size] bytes is plausible for [spec] (the catalog sizes are approximate). */
  fun plausibleSize(spec: ModelSpec, size: Long): Boolean =
    size >= spec.minBytes && size <= spec.approxBytes * 3 / 2

  /**
   * Guesses which catalog model a picked file is, from its [displayName] and [sizeBytes].
   * Best guesses first; empty when nothing matches (the UI then asks the user).
   */
  fun identify(displayName: String?, sizeBytes: Long): List<ModelSpec> {
    val name = displayName?.takeIf { it.isNotBlank() }
    val result = LinkedHashSet<ModelSpec>()

    if (name != null) {
      val s = stem(name)
      // 1. exactly the file name used on Hugging Face (or the one this app stores)
      ModelCatalog.all.filter { s == stem(it.originalFileName) || s == stem(it.fileName) }
        .forEach { result += it }
      if (result.isNotEmpty()) return result.toList()

      // 2. keywords in the name, confirmed by the size when it is known
      val lower = name.lowercase()
      fun byKeyword(spec: ModelSpec, vararg words: String) =
        words.any { lower.contains(it) } && (sizeBytes < 0 || plausibleSize(spec, sizeBytes))
      if (byKeyword(ModelCatalog.EXPLAINER, "0.5b", "0_5b", "0-5b")) result += ModelCatalog.EXPLAINER
      if (byKeyword(ModelCatalog.TRANSLATOR, "1.5b", "1_5b", "1-5b")) result += ModelCatalog.TRANSLATOR
      if (byKeyword(ModelCatalog.FIXER, "phi")) result += ModelCatalog.FIXER
      if (result.isNotEmpty()) return result.toList()
    }

    // 3. only the size
    if (sizeBytes >= 0) ModelCatalog.all.filter { plausibleSize(it, sizeBytes) }.forEach { result += it }
    return result.toList()
  }

  fun looksLikeTask(header: ByteArray, length: Int = header.size): Boolean =
    length >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { header[it] == ZIP_MAGIC[it] }

  /**
   * Copies [input] into [dir] as the model [spec]. The file appears under its final name only when
   * complete and checked, so a failed or cancelled import never leaves a broken model behind.
   *
   * @param declaredSize size reported by the file picker, or -1 when unknown
   * @param freeBytes free space in [dir]'s volume, or -1 when unknown
   * @throws ImportException with the reason
   */
  @Throws(ImportException::class)
  fun import(
    spec: ModelSpec,
    input: InputStream,
    declaredSize: Long,
    dir: File,
    freeBytes: Long = -1,
    progress: ((copied: Long, total: Long) -> Unit)? = null,
    isCancelled: () -> Boolean = { false },
  ): File {
    if (declaredSize in 0 until spec.minBytes) {
      throw ImportException(ImportError.TOO_SMALL, "File is too small for ${spec.displayName}")
    }
    if (declaredSize > 0 && freeBytes >= 0 && freeBytes < declaredSize + SPACE_MARGIN) {
      throw ImportException(ImportError.NO_SPACE, "Not enough free space")
    }
    if (!dir.isDirectory && !dir.mkdirs()) throw ImportException(ImportError.IO, "Cannot create ${dir.path}")

    val part = File(dir, spec.fileName + ".part")
    var copied = 0L
    var lastReport = 0L
    try {
      part.outputStream().use { out ->
        val buf = ByteArray(256 * 1024)
        var first = true
        while (true) {
          if (isCancelled()) throw ImportException(ImportError.CANCELLED, "Cancelled")
          var n = input.read(buf)
          if (n < 0) break
          if (first && n < ZIP_MAGIC.size) {
            // Some file providers return very short first reads: top up so the signature can be checked.
            while (n < ZIP_MAGIC.size) {
              val more = input.read(buf, n, buf.size - n)
              if (more < 0) break
              n += more
            }
          }
          if (first) {
            first = false
            if (!looksLikeTask(buf, n)) {
              throw ImportException(ImportError.NOT_A_MODEL, "Not a MediaPipe .task file")
            }
          }
          out.write(buf, 0, n)
          copied += n
          val now = System.currentTimeMillis()
          if (progress != null && now - lastReport >= 300) {
            lastReport = now
            progress(copied, declaredSize)
          }
        }
        if (first) throw ImportException(ImportError.NOT_A_MODEL, "The file is empty")
      }
      if (copied < spec.minBytes) {
        throw ImportException(ImportError.TOO_SMALL, "File is too small for ${spec.displayName}")
      }
      if (declaredSize >= 0 && copied != declaredSize) {
        throw ImportException(ImportError.IO, "Incomplete copy: $copied of $declaredSize bytes")
      }
      val finalFile = File(dir, spec.fileName)
      if (finalFile.exists() && !finalFile.delete()) {
        throw ImportException(ImportError.IO, "Cannot replace the existing ${finalFile.name}")
      }
      if (!part.renameTo(finalFile)) throw ImportException(ImportError.IO, "Cannot move ${part.name}")
      progress?.invoke(copied, if (declaredSize >= 0) declaredSize else copied)
      return finalFile
    } catch (e: ImportException) {
      part.delete()
      throw e
    } catch (e: IOException) {
      part.delete()
      val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true
      throw ImportException(if (noSpace) ImportError.NO_SPACE else ImportError.IO, e.message ?: "I/O error")
    }
  }
}
