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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.itsaky.androidide.interfaces.IEditorHandler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Explain errors" tab of the editor's bottom sheet. Built from code (no XML) and driven by
 * [ErrorExplainer] and [ModelManager].
 */
class AiExplainFragment : Fragment() {

  private var status: TextView? = null
  private var action: Button? = null
  private var container: LinearLayout? = null
  private var empty: TextView? = null

  private val itemsListener: (List<ExplainItem>) -> Unit = { render(it) }
  private val modelListener: (ModelState) -> Unit = { renderModel(it) }

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  override fun onCreateView(
    inflater: LayoutInflater,
    parent: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    val ctx = requireContext()
    val pad = dp(12)

    val root = LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
    }

    root.addView(Button(ctx).apply {
      text = AiStrings.aiSettings()
      setOnClickListener { startActivity(AiSetupActivity.intent(requireContext(), true)) }
    })
    status = TextView(ctx).also { root.addView(it) }
    action = Button(ctx).also {
      it.visibility = View.GONE
      root.addView(it)
    }
    empty = TextView(ctx).apply {
      text = AiStrings.emptyHint()
      setPadding(0, pad, 0, 0)
    }.also { root.addView(it) }

    container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    root.addView(container)

    return ScrollView(ctx).apply {
      isFillViewport = true
      addView(root)
    }
  }

  override fun onStart() {
    super.onStart()
    ModelManager.refresh(requireContext())
    ModelManager.addListener(modelListener)
    ErrorExplainer.addListener(itemsListener)
  }

  override fun onStop() {
    ModelManager.removeListener(modelListener)
    ErrorExplainer.removeListener(itemsListener)
    super.onStop()
  }

  override fun onDestroyView() {
    status = null
    action = null
    container = null
    empty = null
    super.onDestroyView()
  }

  private fun renderModel(state: ModelState) {
    val tv = status ?: return
    val btn = action ?: return
    val ctx = context ?: return
    val mode = AiSettings.mode(ctx)
    val prefix = AiStrings.modeLine(mode) + "\n"
    btn.visibility = View.GONE
    when (state) {
      is ModelState.NotNeeded -> tv.text = AiStrings.modeLine(mode)
      is ModelState.Ready -> tv.text = prefix + AiStrings.modelReady()
      is ModelState.Downloading -> {
        tv.text = prefix + AiStrings.modelInstalling(state)
        if (!state.copying) {
          btn.text = AiStrings.downloadNowMobile()
          btn.visibility = View.VISIBLE
          btn.setOnClickListener { ModelManager.install(ctx, mode, ignoreNetworkLimit = true) }
        }
      }
      is ModelState.Failed -> {
        tv.text = prefix + AiStrings.modelFailed(state.message)
        btn.text = AiStrings.retry()
        btn.visibility = View.VISIBLE
        btn.setOnClickListener { ModelManager.install(ctx, mode, ignoreNetworkLimit = true) }
      }
      ModelState.Missing -> {
        tv.text = prefix + AiStrings.modelMissing()
        btn.text = AiStrings.downloadNowMobile()
        btn.visibility = View.VISIBLE
        btn.setOnClickListener { ModelManager.install(ctx, mode, ignoreNetworkLimit = true) }
      }
    }
  }

  private fun render(items: List<ExplainItem>) {
    val box = container ?: return
    empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

    // Rebuild only when the number of cards changes; otherwise just update the texts
    // (this runs for every streamed token).
    if (box.childCount != items.size) {
      box.removeAllViews()
      items.forEachIndexed { i, _ -> box.addView(newCard(i)) }
    }
    items.forEachIndexed { i, item -> bind(box.getChildAt(i) as LinearLayout, item) }
  }

  private companion object {
    // child positions inside a card
    const val TITLE = 0
    const val HINT = 1
    const val AI_LABEL = 2
    const val AI = 3
    const val FIX_BUTTON = 4
    const val FIX_TEXT = 5
    const val FIX_PREVIEW = 6
    const val FIX_ACTIONS = 7
  }

  private fun newCard(index: Int): LinearLayout {
    val ctx = requireContext()
    val pad = dp(8)
    return LinearLayout(ctx).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(0, pad, 0, pad * 2)
      addView(TextView(ctx).apply { setTypeface(typeface, Typeface.BOLD); setTextIsSelectable(true) })
      addView(TextView(ctx).apply { setTextIsSelectable(true) })
      addView(TextView(ctx).apply { setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, 0) })
      addView(TextView(ctx).apply { setTextIsSelectable(true) })
      addView(Button(ctx).apply {
        text = AiStrings.proposeFix()
        setOnClickListener { ErrorExplainer.requestFix(requireContext(), index) }
      })
      addView(TextView(ctx).apply { setPadding(0, pad, 0, 0) })
      addView(TextView(ctx).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextIsSelectable(true)
      })
      addView(LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Button(ctx).apply {
          text = AiStrings.applyFix()
          setOnClickListener { applyFix(index) }
        })
        addView(Button(ctx).apply {
          text = AiStrings.undoFix()
          setOnClickListener { undoFix(index) }
        })
      })
    }
  }

  private fun bind(card: LinearLayout, item: ExplainItem) {
    val ctx = context ?: return
    val mode = AiSettings.mode(ctx)
    val title = card.getChildAt(TITLE) as TextView
    val hint = card.getChildAt(HINT) as TextView
    val aiLabel = card.getChildAt(AI_LABEL) as TextView
    val ai = card.getChildAt(AI) as TextView

    val e = item.error
    val where = if (e.file != null) {
      e.file.substringAfterLast('/') + (if (e.line != null) ":${e.line}" else "") + " — "
    } else ""
    title.text = where + e.message

    hint.text = if (item.hint != null) "${AiStrings.hintLabel()}: ${item.hint}" else ""
    hint.visibility = if (item.hint != null) View.VISIBLE else View.GONE

    val aiVisible = item.status != ItemStatus.HINT_ONLY
    aiLabel.visibility = if (aiVisible) View.VISIBLE else View.GONE
    ai.visibility = View.VISIBLE
    aiLabel.text = AiStrings.aiLabel()
    ai.text = when (item.status) {
      ItemStatus.HINT_ONLY -> if (mode == AiMode.ONLINE) AiStrings.onlineNeedsToken()
      else if (mode == AiMode.NONE) AiStrings.noAiChosen() else ""
      ItemStatus.WAITING_MODEL -> AiStrings.waiting()
      ItemStatus.QUEUED -> AiStrings.running()
      ItemStatus.RUNNING -> item.ai.ifBlank { AiStrings.running() }
      ItemStatus.TRANSLATING -> item.ai + "\n\n" + AiStrings.translating()
      ItemStatus.DONE -> item.ai + "\n\n" +
        (if (item.failure != null) AiStrings.translationFailed() + "\n" else "") +
        AiStrings.aiNote(mode)
      ItemStatus.FAILED -> AiStrings.failed(item.failure)
    }

    bindFix(card, item, mode)
  }

  private fun bindFix(card: LinearLayout, item: ExplainItem, mode: AiMode) {
    val button = card.getChildAt(FIX_BUTTON) as Button
    val text = card.getChildAt(FIX_TEXT) as TextView
    val preview = card.getChildAt(FIX_PREVIEW) as TextView
    val actions = card.getChildAt(FIX_ACTIONS) as LinearLayout
    val apply = actions.getChildAt(0) as Button
    val undo = actions.getChildAt(1) as Button

    val fix = item.fix
    // The button is offered when a fix is possible and none is in progress / shown.
    button.visibility =
      if (item.canFix && (fix is FixState.None || fix is FixState.Failed || fix is FixState.NoFix)) View.VISIBLE
      else View.GONE
    text.visibility = View.VISIBLE
    preview.visibility = View.GONE
    actions.visibility = View.GONE
    apply.visibility = View.GONE
    undo.visibility = View.GONE

    when (fix) {
      FixState.None -> {
        text.text = if (!mode.canFix && mode != AiMode.NONE && item.status != ItemStatus.HINT_ONLY) AiStrings.fixNeedsHeavy() else ""
        text.visibility = if (text.text.isEmpty()) View.GONE else View.VISIBLE
      }
      FixState.Generating -> text.text = AiStrings.fixGenerating()
      is FixState.Proposed -> {
        text.text = AiStrings.fixProposed()
        preview.text = fix.preview
        preview.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        apply.visibility = View.VISIBLE
      }
      is FixState.NoFix -> text.text = AiStrings.fixNone(fix.reason)
      is FixState.Failed -> text.text = AiStrings.fixFailed(fix.message)
      is FixState.Applied -> {
        text.text = AiStrings.fixApplied()
        actions.visibility = View.VISIBLE
        undo.visibility = View.VISIBLE
      }
    }
  }

  // ---- applying / undoing a fix ------------------------------------------------------------

  private fun editorHandler(): IEditorHandler? = activity as? IEditorHandler

  private fun applyFix(index: Int) {
    val item = ErrorExplainer.snapshot().getOrNull(index) ?: return
    val path = item.error.file ?: return
    val file = File(path)
    val handler = editorHandler()

    viewLifecycleOwner.lifecycleScope.launch {
      // Prefer the editor's text so unsaved edits are not lost.
      val openEditor = handler?.getEditorForFile(file)?.editor
      val current = openEditor?.text?.toString()
        ?: withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
      if (current == null) {
        toast(AiStrings.fileChanged())
        return@launch
      }

      when (val result = ErrorExplainer.computeFix(index, current)) {
        is ApplyResult.Failure -> toast(AiStrings.fileChanged())
        is ApplyResult.Success -> {
          withContext(Dispatchers.IO) { file.writeText(result.newText) }
          openEditor?.setText(result.newText)
          ErrorExplainer.markApplied(index, file, current)
        }
      }
    }
  }

  private fun undoFix(index: Int) {
    val applied = ErrorExplainer.snapshot().getOrNull(index)?.fix as? FixState.Applied ?: return
    val handler = editorHandler()

    viewLifecycleOwner.lifecycleScope.launch {
      withContext(Dispatchers.IO) { applied.file.writeText(applied.original) }
      handler?.getEditorForFile(applied.file)?.editor?.setText(applied.original)
      ErrorExplainer.markUndone(index)
      toast(AiStrings.fixUndone())
    }
  }

  private fun toast(msg: String) {
    context?.let { Toast.makeText(it, msg, Toast.LENGTH_SHORT).show() }
  }
}
