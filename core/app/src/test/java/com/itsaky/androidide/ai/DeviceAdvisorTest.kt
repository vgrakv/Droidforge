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
import org.junit.Test

class DeviceAdvisorTest {

  private val gb = 1024L * 1024 * 1024

  private fun device(
    ramGb: Double = 8.0,
    freeGb: Double = 50.0,
    abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
    online: Boolean = true,
  ) = DeviceProfile((ramGb * gb).toLong(), (freeGb * gb).toLong(), abis, 34, 8, online)

  @Test
  fun `a strong phone gets every local mode recommended`() {
    val d = device(ramGb = 11.5)
    for (m in listOf(AiMode.BASIC, AiMode.HEBREW, AiMode.HEAVY)) {
      assertThat(DeviceAdvisor.assessMode(m, d).verdict).isEqualTo(Verdict.RECOMMENDED)
    }
  }

  @Test
  fun `a mid phone can do basic and hebrew but heavy is not recommended`() {
    val d = device(ramGb = 7.4) // a marketed "8GB" phone
    assertThat(DeviceAdvisor.assessMode(AiMode.BASIC, d).verdict).isEqualTo(Verdict.RECOMMENDED)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEBREW, d).verdict).isEqualTo(Verdict.RECOMMENDED)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEAVY, d).verdict).isEqualTo(Verdict.NOT_RECOMMENDED)
  }

  @Test
  fun `a weak phone`() {
    val d = device(ramGb = 3.6)
    assertThat(DeviceAdvisor.assessMode(AiMode.BASIC, d).verdict).isEqualTo(Verdict.RECOMMENDED)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEBREW, d).verdict).isEqualTo(Verdict.IMPOSSIBLE)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEAVY, d).verdict).isEqualTo(Verdict.IMPOSSIBLE)
  }

  @Test
  fun `hebrew mode is judged by its worst model`() {
    val d = device(ramGb = 5.5)
    assertThat(DeviceAdvisor.assess(ModelCatalog.EXPLAINER, d).verdict).isEqualTo(Verdict.RECOMMENDED)
    assertThat(DeviceAdvisor.assess(ModelCatalog.TRANSLATOR, d).verdict).isEqualTo(Verdict.NOT_RECOMMENDED)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEBREW, d).verdict).isEqualTo(Verdict.NOT_RECOMMENDED)
  }

  @Test
  fun `storage decides, using the total size of the mode`() {
    val d = device(ramGb = 12.0, freeGb = 1.0)
    assertThat(DeviceAdvisor.assessMode(AiMode.BASIC, d).verdict).isEqualTo(Verdict.RECOMMENDED)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEBREW, d).verdict).isEqualTo(Verdict.IMPOSSIBLE)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEAVY, d).verdict).isEqualTo(Verdict.IMPOSSIBLE)
    assertThat(DeviceAdvisor.assessMode(AiMode.HEAVY, d).reasonHe).contains("מקום")
  }

  @Test
  fun `a 32-bit or x86 cpu cannot run local models`() {
    val d = device(abis = listOf("armeabi-v7a"))
    assertThat(DeviceAdvisor.assessMode(AiMode.BASIC, d).verdict).isEqualTo(Verdict.IMPOSSIBLE)
    assertThat(DeviceAdvisor.assessMode(AiMode.ONLINE, d).verdict).isEqualTo(Verdict.RECOMMENDED)
  }

  @Test
  fun `online needs a connection`() {
    assertThat(DeviceAdvisor.assessMode(AiMode.ONLINE, device(online = false)).verdict)
      .isEqualTo(Verdict.NOT_RECOMMENDED)
  }

  @Test
  fun `reasons exist in both languages`() {
    val a = DeviceAdvisor.assessMode(AiMode.HEAVY, device(ramGb = 3.0))
    assertThat(a.reason(AnswerLanguage.HEBREW)).isNotEmpty()
    assertThat(a.reason(AnswerLanguage.ENGLISH)).isNotEmpty()
    assertThat(a.reasonHe).isNotEqualTo(a.reasonEn)
  }
}
