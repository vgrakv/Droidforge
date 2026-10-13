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

/** Language of the explanation shown to the user. */
enum class AnswerLanguage(val code: String, val promptName: String) {
  HEBREW("he", "Hebrew"),
  ENGLISH("en", "English");

  companion object {
    /** Picks Hebrew for Hebrew locales ("he" or the legacy "iw"), English otherwise. */
    fun fromLocaleCode(code: String?): AnswerLanguage =
      if (code == "he" || code == "iw") HEBREW else ENGLISH
  }
}

/** A well-known class of build errors with a short, reliable, hand-written explanation. */
data class KnownError(
  val id: String,
  val pattern: Regex,
  val en: String,
  val he: String,
) {
  fun hint(lang: AnswerLanguage): String = if (lang == AnswerLanguage.HEBREW) he else en
}

/**
 * Hand-written explanations for the most common build errors.
 *
 * They serve two purposes:
 *  1. They are shown immediately (and always work, even when the model is not downloaded yet).
 *  2. They are injected into the prompt as "known cause", which makes a small language model
 *     far more accurate than asking it to guess from the raw message alone.
 */
object ErrorKnowledgeBase {

  private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

  val entries: List<KnownError> = listOf(
    KnownError(
      "missing-symbol",
      rx("""cannot find symbol|unresolved reference"""),
      "The compiler cannot find a name you used: a class, method or variable. Usual causes: a typo, a missing import, a variable declared in another scope, or a library that is not in the dependencies.",
      "הקומפיילר לא מוצא שם שהשתמשת בו: מחלקה, פונקציה או משתנה. הסיבות הנפוצות: שגיאת כתיב, import חסר, משתנה שהוגדר בתחום אחר, או ספרייה שלא נוספה ל-dependencies.",
    ),
    KnownError(
      "missing-package",
      rx("""package [\w.]+ does not exist"""),
      "The imported package does not exist in the project. Either the import is misspelled or the library that contains it is missing from build.gradle dependencies.",
      "החבילה שייבאת לא קיימת בפרויקט. או שיש טעות בשם ה-import, או שהספרייה שמכילה אותה חסרה ב-dependencies של build.gradle.",
    ),
    KnownError(
      "type-mismatch",
      rx("""incompatible types|type mismatch|required: .* found: """),
      "A value of one type is used where a different type is expected. Convert the value, change the variable's type, or fix what the expression returns.",
      "ערך מסוג אחד משמש במקום שמצפה לסוג אחר. המר את הערך, שנה את סוג המשתנה, או תקן את מה שהביטוי מחזיר.",
    ),
    KnownError(
      "nullability",
      rx("""only safe \(\?\.\) or non-null asserted|nullable receiver|smart cast to .* impossible"""),
      "A value that may be null is used as if it were never null. Use ?. or ?: , check for null first, or make the declared type non-null.",
      "ערך שיכול להיות null משמש כאילו הוא אף פעם לא null. השתמש ב-?. או ב-?: , בדוק null לפני השימוש, או הגדר את הסוג כ-non-null.",
    ),
    KnownError(
      "syntax",
      rx("""';' expected|'\)' expected|reached end of file while parsing|class, interface, enum, or record expected|illegal start of|expecting |unclosed string literal"""),
      "Syntax error: usually a missing semicolon, an unclosed bracket or quote, or an extra closing brace. The real mistake is often on the line above the reported one.",
      "שגיאת תחביר: בדרך כלל נקודה-פסיק חסרה, סוגר או גרשיים שלא נסגרו, או סוגר מסולסל מיותר. הטעות האמיתית נמצאת לא פעם בשורה שמעל השורה המדווחת.",
    ),
    KnownError(
      "missing-return",
      rx("""missing return statement|a 'return' expression required"""),
      "A method that must return a value has a code path that ends without returning anything. Add a return on every path.",
      "פונקציה שחייבת להחזיר ערך מסתיימת במסלול קוד שלא מחזיר כלום. הוסף return בכל מסלול.",
    ),
    KnownError(
      "unreported-exception",
      rx("""unreported exception|unhandled exception"""),
      "The code can throw a checked exception that is neither caught nor declared. Wrap the call in try/catch or add 'throws' to the method.",
      "הקוד עלול לזרוק חריגה מסוג checked שלא נתפסת ולא מוצהרת. עטוף את הקריאה ב-try/catch או הוסף 'throws' לפונקציה.",
    ),
    KnownError(
      "wrong-arguments",
      rx("""cannot be applied to given types|none of the following (functions|candidates) can be called|too many arguments|not enough arguments|no value passed for parameter|cannot be applied to"""),
      "The method is called with the wrong number or types of arguments. Compare the call with the method's signature.",
      "הפונקציה נקראת עם מספר או סוגי פרמטרים לא נכונים. השווה את הקריאה להגדרת הפונקציה.",
    ),
    KnownError(
      "duplicate-definition",
      rx("""is already defined|conflicting declarations|redeclaration|duplicate class"""),
      "The same name is defined twice in the same scope (or the same class exists twice on the classpath). Rename one of them, or remove the duplicate dependency.",
      "אותו שם הוגדר פעמיים באותו תחום (או שאותה מחלקה מופיעה פעמיים ב-classpath). שנה שם לאחד מהם, או הסר את התלות הכפולה.",
    ),
    KnownError(
      "static-context",
      rx("""non-static .* cannot be referenced from a static context"""),
      "An instance member is used from static code. Create an object first, or make the member static.",
      "ניגשים לחבר של מופע מתוך קוד סטטי. צור אובייקט קודם, או הפוך את החבר ל-static.",
    ),
    KnownError(
      "abstract-not-implemented",
      rx("""is not abstract and does not override|overrides nothing|abstract member cannot be accessed"""),
      "A class does not implement every abstract method of its parent/interface (or an @Override does not match any parent method). Implement the missing methods or fix the signature.",
      "מחלקה לא מממשת את כל הפונקציות המופשטות של האב/הממשק (או ש-@Override לא תואם לאף פונקציה באב). ממש את הפונקציות החסרות או תקן את החתימה.",
    ),
    KnownError(
      "dependency-resolution",
      rx("""could not (resolve|find|get)|failed to resolve|unable to resolve dependency|could not download"""),
      "Gradle cannot download a dependency. Check the internet connection, the group:artifact:version spelling, and that the right repository (google(), mavenCentral(), jitpack) is declared.",
      "Gradle לא מצליח להוריד תלות. בדוק חיבור לאינטרנט, את הכתיב של group:artifact:version, ושהמאגר הנכון (google(), mavenCentral(), jitpack) מוגדר.",
    ),
    KnownError(
      "resource-missing",
      rx("""resource .* not found|no resource (found|identifier found)|attribute .* not found|error: style attribute|failed linking references|android resource linking failed"""),
      "A layout/style/manifest refers to a resource or attribute that does not exist. Check the name, that the file is in the right res/ folder, and that the library that defines the attribute is a dependency.",
      "קובץ layout/style/manifest מפנה למשאב או למאפיין שלא קיים. בדוק את השם, שהקובץ בתיקיית res/ הנכונה, ושהספרייה שמגדירה את המאפיין נמצאת ב-dependencies.",
    ),
    KnownError(
      "xml-syntax",
      rx("""error parsing xml|unclosed token|mismatched tag|element type .* must be followed|attribute .* must be declared|not well-formed"""),
      "The XML file is malformed: a tag is not closed or does not match, or an attribute is written incorrectly.",
      "קובץ ה-XML לא תקין: תגית לא נסגרה או לא תואמת, או שמאפיין נכתב לא נכון.",
    ),
    KnownError(
      "manifest-merger",
      rx("""manifest merger failed|merging manifest|uses-sdk:minsdkversion|android:exported"""),
      "The AndroidManifest merge failed. Typical causes: a component with an intent-filter lacks android:exported, a library needs a higher minSdk, or two manifests conflict (use tools:replace).",
      "מיזוג ה-AndroidManifest נכשל. סיבות נפוצות: רכיב עם intent-filter ללא android:exported, ספרייה שדורשת minSdk גבוה יותר, או שני manifests סותרים (השתמש ב-tools:replace).",
    ),
    KnownError(
      "java-version",
      rx("""unsupported class file major version|invalid source release|unsupported major.minor|java version|requires java|jvm target"""),
      "Java/Kotlin version mismatch. Make sure sourceCompatibility, targetCompatibility and kotlinOptions.jvmTarget agree with each other and with the JDK that Gradle runs on.",
      "אי-התאמה בגרסאות Java/Kotlin. ודא ש-sourceCompatibility, targetCompatibility ו-kotlinOptions.jvmTarget תואמים זה לזה וגם ל-JDK ש-Gradle רץ איתו.",
    ),
    KnownError(
      "out-of-memory",
      rx("""java heap space|outofmemoryerror|gc overhead limit|daemon.*(killed|disappeared)"""),
      "The build ran out of memory. Close other apps, lower org.gradle.jvmargs -Xmx to what the device has free, and disable parallel builds.",
      "הבנייה נגמר לה הזיכרון. סגור אפליקציות אחרות, הורד את -Xmx ב-org.gradle.jvmargs לכמות הזיכרון הפנוי במכשיר, וכבה בנייה מקבילית.",
    ),
    KnownError(
      "sdk-location",
      rx("""sdk location not found|sdk\.dir|android_home"""),
      "Gradle cannot find the Android SDK. Set sdk.dir in local.properties (or ANDROID_HOME) to the SDK folder.",
      "Gradle לא מוצא את ה-Android SDK. הגדר sdk.dir בקובץ local.properties (או ANDROID_HOME) לתיקיית ה-SDK.",
    ),
    KnownError(
      "val-reassign",
      rx("""val cannot be reassigned|cannot assign a value to final variable|cannot assign to"""),
      "A value declared as val / final is assigned a second time. Declare it as var (or non-final), or stop reassigning it.",
      "מנסים לשנות ערך שהוגדר כ-val / final. הגדר אותו כ-var (או לא-final), או הפסק לשנות אותו.",
    ),
  )

  /** Returns the first matching entry for [error], or null when nothing is known. */
  fun match(error: BuildError): KnownError? {
    val haystack = buildString {
      append(error.message)
      error.details.take(2).forEach { append(' ').append(it) }
    }
    return entries.firstOrNull { it.pattern.containsMatchIn(haystack) }
  }
}
