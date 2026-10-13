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

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** A short guided tour of the app, shown once after the AI setup and replayable from the settings. */
class AiTourActivity : AppCompatActivity() {

  private class Step(val title: String, val body: String)

  private var index = 0
  private lateinit var steps: List<Step>
  private lateinit var titleView: TextView
  private lateinit var bodyView: TextView
  private lateinit var counter: TextView
  private lateinit var back: Button
  private lateinit var next: Button

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
  private fun t(he: String, en: String) = if (AiStrings.isHebrew) he else en

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    title = t("סיור באפליקציה", "App tour")
    steps = buildSteps()
    index = savedInstanceState?.getInt("index") ?: 0

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), dp(32), dp(24), dp(24))
    }
    counter = TextView(this).apply { textSize = 13f }
    titleView = TextView(this).apply {
      textSize = 24f
      setTypeface(typeface, Typeface.BOLD)
      setPadding(0, dp(8), 0, dp(16))
    }
    bodyView = TextView(this).apply { textSize = 16f; setLineSpacing(0f, 1.2f) }

    val spacer = android.view.View(this)
    val buttons = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }
    back = Button(this).apply { setOnClickListener { go(index - 1) } }
    val skip = Button(this).apply {
      text = t("דלג", "Skip")
      setOnClickListener { finishTour() }
    }
    next = Button(this).apply { setOnClickListener { if (index == steps.lastIndex) finishTour() else go(index + 1) } }
    buttons.addView(back)
    buttons.addView(skip)
    buttons.addView(next)

    root.addView(counter)
    root.addView(titleView)
    root.addView(bodyView)
    root.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))
    root.addView(buttons)
    setContentView(android.widget.ScrollView(this).apply {
      isFillViewport = true
      addView(root)
    })
    render()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putInt("index", index)
  }

  private fun go(i: Int) {
    index = i.coerceIn(0, steps.lastIndex)
    render()
  }

  private fun render() {
    val s = steps[index]
    counter.text = "${index + 1} / ${steps.size}"
    titleView.text = s.title
    bodyView.text = s.body
    back.text = t("הקודם", "Back")
    back.isEnabled = index > 0
    next.text = if (index == steps.lastIndex) t("סיום", "Done") else t("הבא", "Next")
  }

  private fun finishTour() {
    AiSettings.setTourDone(this, true)
    finish()
  }

  private fun buildSteps(): List<Step> {
    val mode = AiSettings.mode(this)
    val fixLine = if (mode.canFix) {
      t(
        "במצב שבחרת יש גם כפתור \"הצע תיקון אוטומטי\": המודל מכין תיקון, אתה רואה מה ישתנה (שורות עם - ו-+), ובלחיצה על \"החל תיקון\" הקובץ מתעדכן. אפשר לבטל בלחיצה.",
        "In your mode there is also a \"Suggest an automatic fix\" button: the model prepares a fix, you see what changes (lines with - and +), and tapping \"Apply fix\" updates the file. You can undo it."
      )
    } else {
      t(
        "תיקון אוטומטי של הקוד זמין במצב כבד ובמצב אונליין. אפשר לעבור אליהם בהגדרות ה-AI.",
        "Automatic code fixes are available in the heavy and online modes. You can switch to them in the AI settings."
      )
    }

    return listOf(
      Step(
        t("ברוך הבא ל-DroidForge", "Welcome to DroidForge"),
        t(
          "זו סביבת פיתוח שבונה אפליקציות אנדרואיד אמיתיות ישירות בטלפון, בלי מחשב. הסיור הזה אורך כדקה ויכסה את הצעדים העיקריים.",
          "This is a development environment that builds real Android apps right on your phone, no computer needed. This tour takes about a minute and covers the main steps."
        )
      ),
      Step(
        t("1. פרויקט חדש", "1. A new project"),
        t(
          "במסך הראשי לחץ על \"Create new project\", בחר תבנית, תן שם וצור. אפשר גם לשכפל פרויקט קיים מ-Git (\"Clone\") או לפתוח את הפרויקט האחרון.",
          "On the main screen tap \"Create new project\", pick a template, name it and create it. You can also clone an existing project from Git (\"Clone\") or reopen the last project."
        )
      ),
      Step(
        t("2. העורך", "2. The editor"),
        t(
          "החלק מקצה המסך כדי לפתוח את עץ הקבצים, ולחץ על קובץ כדי לפתוח אותו. תוך כדי כתיבה העורך מציע השלמות ומסמן שגיאות.",
          "Swipe from the screen edge to open the file tree and tap a file to open it. While you type, the editor suggests completions and marks errors."
        )
      ),
      Step(
        t("3. בנייה והרצה", "3. Build and run"),
        t(
          "כפתור ההרצה בסרגל העליון (\"Quick run\") בונה את האפליקציה ומתקין אותה במכשיר. הבנייה הראשונה איטית וצריכה אינטרנט להורדת ספריות. כל פלט הבנייה מופיע בלשונית \"Build output\" בתחתית.",
          "The run button in the top bar (\"Quick run\") builds your app and installs it on the device. The first build is slow and needs internet to download libraries. All build output appears in the \"Build output\" tab at the bottom."
        )
      ),
      Step(
        t("4. כשהבנייה נכשלת", "4. When a build fails"),
        t(
          "פתח את הלשונית \"הסבר שגיאות\" בתחתית. ההסבר המהיר מופיע מיד, ומתחתיו ניתוח של מודל ה-AI (מצב נוכחי: ${AiStrings.modeName(mode)}). הלוג מנותח אוטומטית, בלי שתצטרך להעתיק כלום.\n\n$fixLine",
          "Open the \"Explain errors\" tab at the bottom. The quick explanation appears immediately, with the AI analysis below it (current mode: ${AiStrings.modeName(mode)}). The log is analyzed automatically, nothing to copy.\n\n$fixLine"
        )
      ),
      Step(
        t("5. הגדרות ה-AI", "5. AI settings"),
        t(
          "בלשונית \"הסבר שגיאות\" לחץ על \"הגדרות AI\" כדי להחליף מצב, להוריד מודל אחר, לעבור בין אונליין לאופליין, למחוק מודלים או טוקן, ולהציג שוב את הסיור הזה.",
          "In the \"Explain errors\" tab tap \"AI settings\" to change mode, download another model, switch between online and offline, delete models or the token, and replay this tour."
        )
      ),
      Step(
        t("מוכן!", "You're ready!"),
        t("בהצלחה בפיתוח. אפשר לסגור את הסיור ולהתחיל ליצור.", "Happy building. Close the tour and start creating.")
      ),
    )
  }
}
