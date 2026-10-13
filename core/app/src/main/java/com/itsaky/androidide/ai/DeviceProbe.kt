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

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.StatFs

/** Reads the facts [DeviceAdvisor] needs from the running device. */
object DeviceProbe {

  fun collect(context: Context): DeviceProfile {
    val app = context.applicationContext
    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

    val free = try {
      StatFs(app.filesDir.absolutePath).availableBytes
    } catch (e: Exception) {
      0L
    }

    val online = try {
      val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      cm.activeNetworkInfo?.isConnected == true
    } catch (e: Exception) {
      false
    }

    return DeviceProfile(
      totalRamBytes = mem.totalMem,
      freeStorageBytes = free,
      abis = Build.SUPPORTED_ABIS.toList(),
      sdkInt = Build.VERSION.SDK_INT,
      cpuCores = Runtime.getRuntime().availableProcessors(),
      hasInternet = online,
    )
  }
}
