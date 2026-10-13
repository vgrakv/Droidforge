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

@file:Suppress("UnstableApiUsage")

import com.itsaky.androidide.build.config.BuildConfig
import com.itsaky.androidide.desugaring.utils.JavaIOReplacements.applyJavaIOReplacements
import com.itsaky.androidide.plugins.AndroidIDEAssetsPlugin
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties

plugins {
  id("com.itsaky.androidide.core-app")
  id("com.android.application")
  id("kotlin-android")
  id("kotlin-kapt")
  id("kotlin-parcelize")
  id("androidx.navigation.safeargs.kotlin")
  id("com.itsaky.androidide.desugaring")
}

apply {
  plugin(AndroidIDEAssetsPlugin::class.java)
}

buildscript {
  dependencies {
    classpath(libs.logging.logback.core)
    classpath(libs.composite.desugaringCore)
  }
}

android {
  namespace = BuildConfig.packageName

  defaultConfig {
    applicationId = BuildConfig.packageName
    vectorDrawables.useSupportLibrary = true
  }

  androidResources {
    generateLocaleConfig = true

    // AI model bundles: keep them stored (not deflated) so installing is a fast copy
    noCompress += listOf("task")
  }

  buildTypes {
    release {
      isShrinkResources = true
    }
  }

  lint {
    abortOnError = false
    disable.addAll(arrayOf("VectorPath", "NestedWeights", "ContentDescription", "SmallSp"))
  }
}

// ---------------------------------------------------------------------------------------------
// Offline builds: -PaiBundle=basic|hebrew packs the AI models inside the APK (see
// ai-models.properties). Without the property nothing is bundled and the app downloads
// models on demand, after the user chooses a mode.
// ---------------------------------------------------------------------------------------------
val aiBundleName: String? = (findProperty("aiBundle") as String?)?.trim()?.takeIf { it.isNotEmpty() }
val aiModelProps = Properties().apply {
  file("ai-models.properties").reader().use { load(it) }
}
val aiBundledModels: List<String> = aiBundleName?.let { name ->
  val spec = aiModelProps.getProperty("bundle.$name")
    ?: throw GradleException("Unknown aiBundle '$name'. Use one of: basic, hebrew.")
  spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }
} ?: emptyList()

val aiAssetsDir = layout.buildDirectory.dir("generated/ai-assets")

val fetchAiModels = tasks.register("fetchAiModels") {
  group = "ai"
  description = "Downloads the AI models selected with -PaiBundle into the APK assets."
  val outDir = aiAssetsDir.get().asFile
  val models = aiBundledModels
  val props = aiModelProps
  onlyIf { models.isNotEmpty() }
  outputs.dir(outDir)
  inputs.property("models", models.joinToString(","))

  doLast {
    val target = File(outDir, "ai-models").apply { mkdirs() }
    // Remove models of a previous, different bundle
    val wanted = models.map { props.getProperty("$it.file") }.toSet()
    target.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }

    for (key in models) {
      val url = props.getProperty("$key.url")
      val fileName = props.getProperty("$key.file")
      val minBytes = props.getProperty("$key.minBytes").toLong()
      val dest = File(target, fileName)
      if (dest.isFile && dest.length() >= minBytes) continue

      logger.lifecycle("Downloading $fileName ...")
      val part = File(target, "$fileName.part")
      var conn = URL(url).openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = true
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      conn.setRequestProperty("User-Agent", "DroidForge-build")
      check(conn.responseCode == 200) { "HTTP ${conn.responseCode} for $url" }
      conn.inputStream.use { input -> part.outputStream().use { out -> input.copyTo(out) } }
      conn.disconnect()
      check(part.length() >= minBytes) { "$fileName is too small (${part.length()} bytes)" }
      part.renameTo(dest)
    }
  }
}

if (aiBundledModels.isNotEmpty()) {
  android {
    sourceSets.getByName("main").assets.srcDir(files(aiAssetsDir).builtBy(fetchAiModels))

    // Bundled builds are for modern phones only: one ABI keeps the (huge) APK count at one.
    splits {
      abi {
        reset()
        include("arm64-v8a")
      }
    }
  }
}

kapt {
  arguments {
    arg("eventBusIndex", "${BuildConfig.packageName}.events.AppEventsIndex")
  }
}

desugaring {
  replacements {
    includePackage(
      "org.eclipse.jgit",
    )

    applyJavaIOReplacements()
  }
}

dependencies {
  debugImplementation(libs.common.leakcanary)

  // Annotation processors
  kapt(libs.common.glide.ap)
  kapt(libs.google.auto.service)
  kapt(projects.annotation.processors)

  implementation(libs.common.editor)
  implementation(libs.common.utilcode)
  implementation(libs.common.glide)
  implementation(libs.common.jsoup)
  implementation(libs.common.kotlin.coroutines.android)
  implementation(libs.common.retrofit)
  implementation(libs.common.retrofit.gson)
  implementation(libs.common.charts)
  implementation(libs.common.hiddenApiBypass)
  implementation(libs.aapt2.common)

  // On-device LLM used by the build-error explainer (com.itsaky.androidide.ai)
  implementation(libs.ai.mediapipe.genai)

  implementation(libs.google.auto.service.annotations)
  implementation(libs.google.gson)
  implementation(libs.google.guava)

  // Git
  implementation(libs.git.jgit)

  // AndroidX
  implementation(libs.androidx.splashscreen)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.cardview)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.coordinatorlayout)
  implementation(libs.androidx.drawer)
  implementation(libs.androidx.grid)
  implementation(libs.androidx.nav.fragment)
  implementation(libs.androidx.nav.ui)
  implementation(libs.androidx.preference)
  implementation(libs.androidx.recyclerview)
  implementation(libs.androidx.transition)
  implementation(libs.androidx.vectors)
  implementation(libs.androidx.animated.vectors)
  implementation(libs.androidx.work)
  implementation(libs.androidx.work.ktx)
  implementation(libs.google.material)
  implementation(libs.google.flexbox)

  // Kotlin
  implementation(libs.androidx.core.ktx)
  implementation(libs.common.kotlin)

  // Dependencies in composite build
  implementation(libs.composite.appintro)
  implementation(libs.composite.desugaringCore)
  implementation(libs.composite.javapoet)

  // Local projects here
  implementation(projects.core.actions)
  implementation(projects.core.common)
  implementation(projects.core.indexingApi)
  implementation(projects.core.indexingCore)
  implementation(projects.core.lspApi)
  implementation(projects.core.projects)
  implementation(projects.core.resources)
  implementation(projects.editor.impl)
  implementation(projects.editor.lexers)
  implementation(projects.event.eventbus)
  implementation(projects.event.eventbusAndroid)
  implementation(projects.event.eventbusEvents)
  implementation(projects.java.javacServices)
  implementation(projects.java.lsp)
  implementation(projects.logging.idestats)
  implementation(projects.logging.logsender)
  implementation(projects.termux.application)
  implementation(projects.termux.view)
  implementation(projects.termux.emulator)
  implementation(projects.termux.shared)
  implementation(projects.tooling.api)
  implementation(projects.tooling.pluginConfig)
  implementation(projects.utilities.buildInfo)
  implementation(projects.utilities.lookup)
  implementation(projects.utilities.preferences)
  implementation(projects.utilities.templatesApi)
  implementation(projects.utilities.templatesImpl)
  implementation(projects.utilities.treeview)
  implementation(projects.utilities.uidesigner)
  implementation(projects.utilities.xmlInflater)
  implementation(projects.xml.aaptcompiler)
  implementation(projects.xml.lsp)
  implementation(projects.xml.utils)

  // This is to build the tooling-api-impl project before the app is built
  // So we always copy the latest JAR file to assets
  compileOnly(projects.tooling.impl)

  testImplementation(projects.testing.unitTest)
  androidTestImplementation(projects.testing.androidTest)
}
