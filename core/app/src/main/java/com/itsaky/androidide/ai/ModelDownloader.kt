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
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resumable HTTP downloader (no Android dependencies).
 *
 * - Writes to `<name>.part` and renames to the final name only when complete, so a half-finished
 *   file is never mistaken for a model.
 * - Resumes with HTTP `Range` after an interruption.
 * - Follows redirects manually so the `Range` header is kept (Hugging Face redirects to a CDN).
 */
class ModelDownloader(
  private val connectTimeoutMs: Int = 20_000,
  private val readTimeoutMs: Int = 30_000,
) {

  fun interface Progress {
    fun onProgress(downloaded: Long, total: Long)
  }

  /** Thrown when the user/system cancels the download. */
  class CancelledException : IOException("Download cancelled")

  /** Returns the final model file. Throws [IOException] on failure (the caller may retry). */
  @Throws(IOException::class)
  fun download(
    spec: ModelSpec,
    dir: File,
    progress: Progress? = null,
    isCancelled: () -> Boolean = { false },
  ): File {
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")

    val finalFile = File(dir, spec.fileName)
    if (finalFile.isFile && finalFile.length() >= spec.minBytes) return finalFile

    val part = File(dir, spec.fileName + ".part")
    var attempts = 0
    while (true) {
      val existing = if (part.isFile) part.length() else 0L
      val conn = open(spec.url, existing)
      try {
        val code = conn.responseCode
        when {
          code == 416 -> {
            // We asked for bytes beyond the end: the .part is already complete (or garbage).
            if (part.length() >= spec.minBytes) return finish(part, finalFile, spec)
            part.delete()
            if (++attempts > 1) throw IOException("HTTP 416 after restart")
            continue
          }
          code == 206 -> copy(conn, part, append = true, offset = existing, progress, isCancelled)
          code == 200 -> copy(conn, part, append = false, offset = 0, progress, isCancelled)
          else -> throw IOException("HTTP $code for ${spec.url}")
        }
      } finally {
        conn.disconnect()
      }
      return finish(part, finalFile, spec)
    }
  }

  private fun finish(part: File, finalFile: File, spec: ModelSpec): File {
    if (part.length() < spec.minBytes) {
      val size = part.length()
      part.delete()
      throw IOException("Downloaded file is too small ($size bytes), expected at least ${spec.minBytes}")
    }
    if (finalFile.exists()) finalFile.delete()
    if (!part.renameTo(finalFile)) throw IOException("Cannot move ${part.name} to ${finalFile.name}")
    return finalFile
  }

  private fun copy(
    conn: HttpURLConnection,
    part: File,
    append: Boolean,
    offset: Long,
    progress: Progress?,
    isCancelled: () -> Boolean,
  ) {
    // For 200 the length is the full size; for 206 it is only the remaining bytes.
    val contentLength = conn.contentLengthLong
    val total = if (contentLength < 0) -1L else contentLength + offset

    val buffer = ByteArray(64 * 1024)
    var downloaded = offset
    var lastReport = 0L

    RandomAccessFile(part, "rw").use { out ->
      if (append) out.seek(offset) else out.setLength(0)
      conn.inputStream.use { input ->
        while (true) {
          if (isCancelled()) throw CancelledException()
          val n = input.read(buffer)
          if (n < 0) break
          out.write(buffer, 0, n)
          downloaded += n
          val now = System.currentTimeMillis()
          if (progress != null && now - lastReport >= 500) {
            lastReport = now
            progress.onProgress(downloaded, total)
          }
        }
      }
    }

    if (total >= 0 && downloaded != total) {
      // Connection dropped early: keep the .part so the next attempt can resume.
      throw IOException("Incomplete download: $downloaded of $total bytes")
    }
    progress?.onProgress(downloaded, if (total >= 0) total else downloaded)
  }

  private fun open(startUrl: String, resumeFrom: Long): HttpURLConnection {
    var url = startUrl
    repeat(6) {
      val conn = URL(url).openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = false
      conn.connectTimeout = connectTimeoutMs
      conn.readTimeout = readTimeoutMs
      conn.setRequestProperty("User-Agent", "AndroidIDE-ModelDownloader")
      if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
      val code = conn.responseCode
      if (code in 300..399) {
        val location = conn.getHeaderField("Location")
          ?: throw IOException("Redirect without Location header")
        conn.disconnect()
        url = URL(URL(url), location).toString()
      } else {
        return conn
      }
    }
    throw IOException("Too many redirects")
  }
}
