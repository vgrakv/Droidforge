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

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run chooser ("how should the AI work?") and, later, the AI settings screen.
 * Built from code, no XML.
 */
class AiSetupActivity : AppCompatActivity() {

  companion object {
    private const val EXTRA_SETTINGS = "settings"
    private const val REQ_PICK_MODEL = 4711
    private const val MODELS_PAGE = "https://huggingface.co/litert-community"

    fun intent(context: Context, settings: Boolean): Intent =
      Intent(context, AiSetupActivity::class.java).putExtra(EXTRA_SETTINGS, settings)

    private val MODES = listOf(AiMode.BASIC, AiMode.HEBREW, AiMode.HEAVY, AiMode.ONLINE)
    private const val GREEN = 0xFF2E7D32.toInt()
    private const val ORANGE = 0xFFEF6C00.toInt()
    private const val RED = 0xFFC62828.toInt()
  }

  private var settingsMode = false
  private var selectedMode = AiMode.BASIC
  private var selectedProvider: OnlineProvider? = null
  private var device: DeviceProfile? = null

  private val radios = mutableMapOf<AiMode, RadioButton>()
  private val verdictViews = mutableMapOf<AiMode, TextView>()
  private val providerRadios = mutableMapOf<String, RadioButton>()

  private lateinit var deviceInfo: TextView
  private lateinit var onlineSection: LinearLayout
  private lateinit var tokenInput: EditText
  private lateinit var modelInput: EditText
  private lateinit var urlInput: EditText
  private lateinit var testResult: TextView
  private lateinit var wifiBox: CheckBox
  private var importDialog: AlertDialog? = null
  private var importProgress: TextView? = null

  @Volatile
  private var importCancelled = false

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    settingsMode = intent.getBooleanExtra(EXTRA_SETTINGS, false)
    title = if (settingsMode) AiStrings.settingsTitle() else AiStrings.setupTitle()

    val current = AiSettings.mode(this)
    selectedMode = when {
      current != AiMode.NONE -> current
      else -> preselectForBundled()
    }
    selectedProvider = AiSettings.provider(this)

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(24))
    }
    root.addView(text(AiStrings.setupTitle(), 22f, bold = true))
    root.addView(text(AiStrings.setupIntro()).also { it.setPadding(0, dp(6), 0, dp(10)) })

    val bundled = ModelManager.bundledSpecs(this)
    if (bundled.isNotEmpty()) {
      root.addView(
        text(AiStrings.bundledBanner(bundled.joinToString { it.displayName }), bold = true)
          .also { it.setPadding(0, 0, 0, dp(10)) }
      )
    }

    root.addView(Button(this).apply {
      text = AiStrings.checkDevice()
      setOnClickListener { runDeviceCheck() }
    })
    deviceInfo = text("").also { it.setPadding(0, dp(6), 0, dp(10)) }
    root.addView(deviceInfo)

    MODES.forEach { root.addView(modeCard(it)) }

    onlineSection = buildOnlineSection()
    root.addView(onlineSection)

    root.addView(buildImportSection())
    root.addView(buildLibrarySection())

    wifiBox = CheckBox(this).apply {
      text = AiStrings.wifiOnly()
      isChecked = AiSettings.isWifiOnly(this@AiSetupActivity)
    }
    root.addView(wifiBox)

    if (settingsMode) root.addView(buildSettingsExtras())

    root.addView(Button(this).apply {
      text = AiStrings.confirm()
      setOnClickListener { onConfirm() }
    })
    root.addView(Button(this).apply {
      text = if (settingsMode) AiStrings.cancel() else AiStrings.skip()
      setOnClickListener { if (settingsMode) finish() else onSkip() }
    })

    setContentView(ScrollView(this).apply { addView(root) })
    refreshSelection()
  }

  /** In an offline build the bundled models decide the default choice. */
  private fun preselectForBundled(): AiMode {
    val ids = ModelManager.bundledSpecs(this).map { it.role }.toSet()
    return when {
      ModelRole.TRANSLATOR in ids -> AiMode.HEBREW
      else -> AiMode.BASIC
    }
  }

  // ---- building blocks ---------------------------------------------------------------------

  private fun text(s: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = size
    if (bold) setTypeface(typeface, Typeface.BOLD)
  }

  private fun modeCard(mode: AiMode): View {
    val card = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(8), dp(8), dp(8), dp(12))
      setOnClickListener { select(mode) }
    }
    val radio = RadioButton(this).apply {
      text = AiStrings.modeTitle(mode)
      setTypeface(typeface, Typeface.BOLD)
      setOnClickListener { select(mode) }
    }
    radios[mode] = radio
    card.addView(radio)
    card.addView(text(AiStrings.modeDescription(mode)))

    val models = ModelCatalog.forMode(mode)
    if (models.isNotEmpty()) {
      val bundled = ModelManager.bundledSpecs(this).map { it.id }.toSet()
      card.addView(
        text(models.joinToString("\n") {
          val line = AiStrings.modelSize(it.displayName, it.approxBytes / (1024 * 1024))
          if (it.id in bundled) "$line ✓" else line
        }).also { it.setPadding(0, dp(4), 0, 0) }
      )
    }
    val verdict = text("", bold = true).also { it.setPadding(0, dp(4), 0, 0) }
    verdictViews[mode] = verdict
    card.addView(verdict)
    return card
  }

  private fun select(mode: AiMode) {
    selectedMode = mode
    refreshSelection()
  }

  private fun refreshSelection() {
    radios.forEach { (m, r) -> r.isChecked = m == selectedMode }
    onlineSection.visibility = if (selectedMode == AiMode.ONLINE) View.VISIBLE else View.GONE
  }

  // ---- device check ------------------------------------------------------------------------

  private fun runDeviceCheck() {
    val d = DeviceProbe.collect(this)
    device = d
    val gb = 1024.0 * 1024 * 1024
    deviceInfo.text = AiStrings.deviceFacts(
      String.format(java.util.Locale.US, "%.1f", d.totalRamBytes / gb),
      String.format(java.util.Locale.US, "%.1f", d.freeStorageBytes / gb),
      d.abis.firstOrNull() ?: "?",
      d.sdkInt,
      d.cpuCores,
    )

    for (mode in MODES) {
      val view = verdictViews[mode] ?: continue
      val sb = StringBuilder()
      val overall = DeviceAdvisor.assessMode(mode, d)
      sb.append(AiStrings.verdict(overall.verdict)).append(": ").append(overall.reason(AiStrings.lang))
      // Per-model detail for modes with more than one model.
      val specs = ModelCatalog.forMode(mode)
      if (specs.size > 1) {
        specs.forEach {
          val a = DeviceAdvisor.assess(it, d)
          sb.append("\n• ").append(it.displayName).append(" – ").append(AiStrings.verdict(a.verdict))
        }
      }
      view.text = sb.toString()
      view.setTextColor(colorOf(overall.verdict))
    }
  }

  private fun colorOf(v: Verdict) = when (v) {
    Verdict.RECOMMENDED -> GREEN
    Verdict.NOT_RECOMMENDED -> ORANGE
    Verdict.IMPOSSIBLE -> RED
  }

  // ---- online section ----------------------------------------------------------------------

  private fun buildOnlineSection(): LinearLayout {
    val section = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(8), dp(4), dp(8), dp(12))
    }
    section.addView(text(AiStrings.onlineHeader(), bold = true))

    OnlineProviders.all.forEach { p ->
      val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(4))
      }
      val radio = RadioButton(this).apply {
        text = p.name + " — " + AiStrings.freeTier(p)
        setTypeface(typeface, Typeface.BOLD)
        setOnClickListener { chooseProvider(p) }
      }
      providerRadios[p.id] = radio
      box.addView(radio)
      box.addView(text(AiStrings.abilities(p)))
      box.addView(text(AiStrings.note(p)))
      p.tokenUrl?.let { url ->
        box.addView(Button(this).apply {
          text = AiStrings.createToken()
          setOnClickListener { openLink(url) }
        })
      }
      section.addView(box)
    }

    tokenInput = EditText(this).apply {
      hint = AiStrings.tokenHint()
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
      setSingleLine()
    }
    modelInput = EditText(this).apply { hint = AiStrings.modelHint(); setSingleLine() }
    urlInput = EditText(this).apply {
      hint = AiStrings.urlHint()
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      setSingleLine()
      visibility = View.GONE
    }
    testResult = text("")

    section.addView(urlInput)
    section.addView(tokenInput)
    section.addView(modelInput)
    section.addView(Button(this).apply {
      text = AiStrings.testConnection()
      setOnClickListener { testConnection() }
    })
    section.addView(testResult)

    selectedProvider?.let { chooseProvider(it, keepToken = true) }
    return section
  }

  private fun chooseProvider(p: OnlineProvider, keepToken: Boolean = false) {
    selectedProvider = p
    providerRadios.forEach { (id, r) -> r.isChecked = id == p.id }
    modelInput.setText(AiSettings.onlineModel(this, p))
    urlInput.visibility = if (p.customBaseUrl) View.VISIBLE else View.GONE
    urlInput.setText(AiSettings.baseUrl(this, p))
    // The saved token is never shown again; leaving the box empty keeps it.
    if (!keepToken) tokenInput.setText("")
    tokenInput.hint = if (!AiSettings.token(this, p).isNullOrBlank()) "••••••••" else AiStrings.tokenHint()
    testResult.text = ""
  }

  private fun openLink(url: String) {
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
      Toast.makeText(this, url, Toast.LENGTH_LONG).show()
    }
  }

  /** Saves the online fields. Returns false (after telling the user) when something is missing. */
  private fun saveOnlineFields(): Boolean {
    val p = selectedProvider ?: run {
      Toast.makeText(this, AiStrings.pickProviderFirst(), Toast.LENGTH_SHORT).show()
      return false
    }
    val token = tokenInput.text.toString().trim()
    if (token.isNotEmpty()) {
      if (!AiSettings.setToken(this, p, token)) {
        Toast.makeText(this, AiStrings.tokenStoreFailed(), Toast.LENGTH_LONG).show()
        return false
      }
    } else if (AiSettings.token(this, p).isNullOrBlank() && !p.customBaseUrl) {
      Toast.makeText(this, AiStrings.tokenMissing(), Toast.LENGTH_SHORT).show()
      return false
    }
    AiSettings.setOnlineModel(this, p, modelInput.text.toString())
    if (p.customBaseUrl) AiSettings.setBaseUrl(this, p, urlInput.text.toString())
    AiSettings.setProviderId(this, p.id)
    tokenInput.setText("")
    return true
  }

  private fun testConnection() {
    if (!saveOnlineFields()) return
    testResult.text = AiStrings.testing()
    testResult.setTextColor(Color.GRAY)
    lifecycleScope.launch {
      try {
        OnlineClient.complete(
          this@AiSetupActivity, "Reply with the single word OK.", "Say OK.", 16
        )
        testResult.text = AiStrings.testOk()
        testResult.setTextColor(GREEN)
      } catch (e: Exception) {
        testResult.text = AiStrings.testFailed(e.message ?: e.javaClass.simpleName)
        testResult.setTextColor(RED)
      }
    }
  }


  // ---- load a model from a file / manual download ------------------------------------------

  private fun buildImportSection(): View {
    val box = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(8), dp(12), dp(8), dp(8))
    }
    box.addView(text(AiStrings.importHeader(), bold = true))
    box.addView(text(AiStrings.importExplain()))
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val lp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
    row.addView(Button(this).apply {
      text = AiStrings.importButton()
      setOnClickListener { pickModelFile() }
    }, lp())
    row.addView(Button(this).apply {
      text = AiStrings.browserButton()
      setOnClickListener { showBrowserDownloadDialog() }
    }, lp())
    box.addView(row)
    return box
  }

  /** A dialog with "open in browser" buttons for the files of all models. */
  private fun showBrowserDownloadDialog() {
    val content = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(8), dp(16), dp(8))
    }
    content.addView(text(AiStrings.browserDialogIntro()))
    ModelCatalog.all.forEach { m ->
      content.addView(modelInfo(m).also { it.setPadding(0, dp(14), 0, 0) })
      content.addView(Button(this).apply {
        text = AiStrings.openInBrowser()
        setOnClickListener { openLink(m.url) }
      })
      content.addView(Button(this).apply {
        text = AiStrings.copyLink()
        setOnClickListener { copyLink(m) }
      })
    }
    content.addView(Button(this).apply {
      text = AiStrings.browseAllModels()
      setOnClickListener { openLink(MODELS_PAGE) }
    }.also { (it.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(12) })
    AlertDialog.Builder(this)
      .setTitle(AiStrings.browserDialogTitle())
      .setView(ScrollView(this).apply { addView(content) })
      .setPositiveButton(AiStrings.close(), null)
      .show()
  }

  private fun modelInfo(m: ModelSpec): TextView {
    val note = AiStrings.modelNote(m)
    return text(
      m.displayName + " — " + AiStrings.modelSizeText(m) + "\n" + AiStrings.roleLabel(m.role) +
        (if (note.isNotBlank()) "\n$note" else "") + "\n" + AiStrings.fileNameLine(m.originalFileName)
    ).also { it.setTypeface(it.typeface, Typeface.NORMAL) }
  }

  private fun copyLink(m: ModelSpec) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(m.displayName, m.url))
    Toast.makeText(this, AiStrings.linkCopied(), Toast.LENGTH_SHORT).show()
  }

  /** In-app downloads of every model; the chosen one replaces the default of its role. */
  private fun buildLibrarySection(): View {
    val box = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(8), dp(8), dp(8), dp(12))
    }
    box.addView(text(AiStrings.libraryHeader(), bold = true))
    box.addView(text(AiStrings.libraryExplain()))
    ModelCatalog.all.forEach { m ->
      val installed = ModelManager.isInstalled(this, m)
      val inUse = (AiSettings.modelChoice(this, m.role) ?: ModelCatalog.forRole(m.role).first().id) == m.id
      val state = when {
        installed && inUse -> "\n" + AiStrings.stateInUse()
        installed -> "\n" + AiStrings.stateInstalled()
        else -> ""
      }
      box.addView(modelInfo(m).also { it.text = it.text.toString() + state; it.setPadding(0, dp(12), 0, 0) })
      box.addView(Button(this).apply {
        text = if (installed) AiStrings.useThis() else AiStrings.downloadInApp()
        setOnClickListener { if (installed) useModel(m) else confirmLibraryDownload(m) }
      })
    }
    return box
  }

  private fun useModel(m: ModelSpec) {
    AiSettings.setModelChoice(this, m)
    ModelManager.refresh(this)
    Toast.makeText(this, AiStrings.nowUsing(m.displayName), Toast.LENGTH_SHORT).show()
    recreate()
  }

  private fun confirmLibraryDownload(m: ModelSpec) {
    AlertDialog.Builder(this)
      .setMessage(AiStrings.libraryDownloadConfirm(m.displayName, AiStrings.modelSizeText(m)))
      .setPositiveButton(AiStrings.yes()) { _, _ ->
        AiSettings.setWifiOnly(this, wifiBox.isChecked)
        AiSettings.setModelChoice(this, m)
        ModelManager.installSpecs(this, listOf(m))
        Toast.makeText(this, AiStrings.downloadStarted(), Toast.LENGTH_SHORT).show()
      }
      .setNegativeButton(AiStrings.no(), null)
      .show()
  }

  private fun pickModelFile() {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
      .addCategory(Intent.CATEGORY_OPENABLE)
      .setType("*/*")
    @Suppress("DEPRECATION")
    startActivityForResult(intent, REQ_PICK_MODEL)
  }

  @Suppress("DEPRECATION")
  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == REQ_PICK_MODEL && resultCode == RESULT_OK) {
      data?.data?.let { onModelPicked(it) }
    }
  }

  private fun onModelPicked(uri: Uri) {
    var name: String? = null
    var size = -1L
    try {
      contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
          if (c.moveToFirst()) {
            name = c.getString(0)
            if (!c.isNull(1)) size = c.getLong(1)
          }
        }
    } catch (e: Exception) {
      // some providers do not report name/size: we ask the user which model it is
    }

    val candidates = ModelImporter.identify(name, size)
    if (candidates.size == 1) {
      val spec = candidates[0]
      AlertDialog.Builder(this)
        .setMessage(AiStrings.confirmImport(spec.displayName, spec.approxBytes / (1024 * 1024)))
        .setPositiveButton(AiStrings.yes()) { _, _ -> runImport(uri, spec, size) }
        .setNegativeButton(AiStrings.no(), null)
        .show()
    } else {
      // Unknown or ambiguous: let the user say which model the file is.
      val options = candidates.ifEmpty { ModelCatalog.all }
      AlertDialog.Builder(this)
        .setTitle(AiStrings.whichModel())
        .setItems(options.map { AiStrings.modelSize(it.displayName, it.approxBytes / (1024 * 1024)) + " – " + AiStrings.roleLabel(it.role) }.toTypedArray()) { _, which ->
          runImport(uri, options[which], size)
        }
        .setNegativeButton(AiStrings.cancel(), null)
        .show()
    }
  }

  private fun runImport(uri: Uri, spec: ModelSpec, size: Long) {
    importCancelled = false
    val label = text(AiStrings.importing(spec.displayName, 0, size)).also { it.setPadding(dp(24), dp(16), dp(24), dp(8)) }
    importProgress = label
    importDialog = AlertDialog.Builder(this)
      .setView(label)
      .setCancelable(false)
      .setNegativeButton(AiStrings.cancel()) { _, _ -> importCancelled = true }
      .show()

    val dir = ModelManager.modelDir(this)
    lifecycleScope.launch {
      val outcome: Result<File> = withContext(Dispatchers.IO) {
        runCatching {
          dir.mkdirs()
          val free = try { StatFs(dir.absolutePath).availableBytes } catch (e: Exception) { -1L }
          val input = contentResolver.openInputStream(uri) ?: throw ImportException(ImportError.IO, AiStrings.cannotReadFile())
          input.use {
            ModelImporter.import(
              spec = spec,
              input = it,
              declaredSize = size,
              dir = dir,
              freeBytes = free,
              progress = { done, total -> runOnUiThread { importProgress?.text = AiStrings.importing(spec.displayName, done, total) } },
              isCancelled = { importCancelled },
            )
          }
        }
      }
      importDialog?.dismiss()
      importDialog = null
      importProgress = null
      if (outcome.isSuccess) AiSettings.setModelChoice(this@AiSetupActivity, spec)
      ModelManager.refresh(this@AiSetupActivity)

      val failure = outcome.exceptionOrNull()
      val message = when {
        failure == null -> AiStrings.importDone(spec.displayName)
        failure is ImportException -> AiStrings.importError(failure.error, failure.message)
        else -> AiStrings.importError(ImportError.IO, failure.message)
      }
      Toast.makeText(this@AiSetupActivity, message, Toast.LENGTH_LONG).show()
    }
  }

  // ---- settings extras ---------------------------------------------------------------------

  private fun buildSettingsExtras(): View {
    val box = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, dp(12), 0, dp(12))
    }
    box.addView(text(
      AiStrings.currentStatus(
        AiSettings.mode(this), ModelManager.usedBytes(this) / (1024 * 1024)
      ),
      bold = true
    ))
    box.addView(Button(this).apply {
      text = AiStrings.deleteModels()
      setOnClickListener {
        ModelManager.deleteAll(this@AiSetupActivity)
        Toast.makeText(this@AiSetupActivity, AiStrings.modelsDeleted(), Toast.LENGTH_SHORT).show()
        recreate()
      }
    })
    box.addView(Button(this).apply {
      text = AiStrings.clearToken()
      setOnClickListener {
        selectedProvider?.let { AiSettings.clearToken(this@AiSetupActivity, it) }
        Toast.makeText(this@AiSetupActivity, AiStrings.tokenCleared(), Toast.LENGTH_SHORT).show()
      }
    })
    box.addView(Button(this).apply {
      text = AiStrings.replayTour()
      setOnClickListener { startActivity(Intent(this@AiSetupActivity, AiTourActivity::class.java)) }
    })
    return box
  }

  // ---- confirm / skip ----------------------------------------------------------------------

  private fun onSkip() {
    AiSettings.setMode(this, AiMode.NONE)
    ModelManager.refresh(this)
    finishSetup()
  }

  private fun onConfirm() {
    val mode = selectedMode
    AiSettings.setWifiOnly(this, wifiBox.isChecked)

    if (mode == AiMode.ONLINE) {
      if (!saveOnlineFields()) return
      applyMode(mode)
      return
    }

    // A local mode: warn when the device check says it will not work well.
    val d = device ?: DeviceProbe.collect(this).also { device = it }
    val verdict = DeviceAdvisor.assessMode(mode, d)
    if (verdict.verdict != Verdict.RECOMMENDED) {
      AlertDialog.Builder(this)
        .setMessage(AiStrings.deviceWarning(verdict.reason(AiStrings.lang)))
        .setPositiveButton(AiStrings.yes()) { _, _ -> confirmDownload(mode) }
        .setNegativeButton(AiStrings.no(), null)
        .show()
    } else {
      confirmDownload(mode)
    }
  }

  private fun confirmDownload(mode: AiMode) {
    val missing = ModelManager.specsFor(this, mode).filterNot { ModelManager.isInstalled(this, it) }
    if (missing.isEmpty()) {
      applyMode(mode)
      return
    }
    val bundled = ModelManager.bundledSpecs(this).map { it.id }.toSet()
    val allBundled = missing.all { it.id in bundled }
    val mb = missing.sumOf { it.approxBytes } / (1024 * 1024)
    AlertDialog.Builder(this)
      .setMessage(AiStrings.downloadConfirm(mb, allBundled))
      .setPositiveButton(AiStrings.yes()) { _, _ -> applyMode(mode) }
      .setNegativeButton(AiStrings.no(), null)
      .show()
  }

  private fun applyMode(mode: AiMode) {
    AiSettings.setMode(this, mode)
    if (mode.isLocal) ModelManager.install(this, mode) else ModelManager.refresh(this)
    Toast.makeText(this, AiStrings.saved(), Toast.LENGTH_SHORT).show()
    finishSetup()
  }

  private fun finishSetup() {
    val first = !AiSettings.isSetupDone(this)
    AiSettings.setSetupDone(this, true)
    if (first && !AiSettings.isTourDone(this)) {
      startActivity(Intent(this, AiTourActivity::class.java))
    }
    finish()
  }
}
