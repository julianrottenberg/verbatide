package com.julianrottenberg.verbatide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {
    private lateinit var search: EditText
    private lateinit var filtered: LinearLayout
    private var entries: List<HistoryEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(attrColor(android.R.attr.colorBackground))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }

        // Title bar with back button
        val titleBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(16))
            }
        val back =
            TextView(this).apply {
                text = "←  Back"
                textSize = 16f
                setTextColor(attrColor(android.R.attr.textColorPrimary))
                setPadding(0, 0, dp(16), 0)
                isClickable = true
                setOnClickListener { finish() }
            }
        val title =
            TextView(this).apply {
                text = "History"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(attrColor(android.R.attr.textColorPrimary))
            }
        titleBar.addView(back)
        titleBar.addView(title)
        root.addView(titleBar)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        search =
            EditText(this).apply {
                hint = "Search history…"
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setSingleLine()
            }
        list.addView(search)

        filtered = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(filtered)

        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    render(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            },
        )

        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** Re-reads the file, then renders. Only for mutations and resume — the search
     *  box filters the list it already has. */
    private fun reload() {
        entries = HistoryManager.load(this).sortedByDescending { it.ts }
        render(search.text.toString().trim())
    }

    private fun render(filter: String = "") {
        filtered.removeAllViews()
        // Filter in memory: re-reading and re-parsing the whole JSON file on every
        // keystroke of the search box was the reason typing felt heavy.
        val items = entries.filter { filter.isBlank() || it.text.contains(filter, true) || it.summarize().contains(filter, true) }
        if (items.isEmpty()) {
            filtered.addView(
                TextView(this).apply {
                    text = if (entries.isEmpty()) "No transcriptions yet." else "No matches."
                    textSize = 14f
                    setPadding(dp(12), dp(16), 0, 0)
                    setTextColor(attrColor(android.R.attr.textColorSecondary))
                },
            )
            return
        }
        val df = SimpleDateFormat("MMM dd · HH:mm", Locale.getDefault())
        for (e in items) {
            val card =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
                }
            val stamp =
                TextView(this).apply {
                    text = df.format(Date(e.ts))
                    textSize = 12f
                    setTextColor(attrColor(android.R.attr.textColorSecondary))
                }
            val meta =
                TextView(this).apply {
                    text = e.summarize()
                    textSize = 11f
                    setTextColor(attrColor(android.R.attr.textColorSecondary))
                    setPadding(0, dp(2), 0, 0)
                }
            val body =
                TextView(this).apply {
                    text = e.text
                    textSize = 16f
                    setTextColor(attrColor(android.R.attr.textColorPrimary))
                    ellipsize = TextUtils.TruncateAt.END
                    maxLines = 3
                    setPadding(0, dp(6), 0, 0)
                }
            card.addView(stamp)
            card.addView(meta)
            card.addView(body)

            card.isClickable = true
            card.setOnClickListener { showDetails(e) }
            card.setOnLongClickListener {
                val actions = ArrayList<Pair<String, () -> Unit>>()
                if (e.recording.isNotBlank()) {
                    actions += "Re-transcribe from kept audio" to { reRunTranscription(e) }
                }
                if (e.rawText.ifBlank { e.text }.isNotBlank()) {
                    actions += "Clean up again" to { reRunCleanup(e) }
                }
                actions += "Delete" to { confirmDelete(e) }
                AlertDialog
                    .Builder(this)
                    .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                        actions.getOrNull(which)?.second?.invoke()
                    }.show()
                true
            }

            val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            filtered.addView(card, params)
        }
    }

    /** Full record for one transcription: text plus every field we store. */
    private fun showDetails(e: HistoryEntry) {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val details =
            listOf(
                "Time" to df.format(Date(e.ts)),
                "Transcript provider" to e.provider.ifBlank { "—" },
                "Fallback slot" to if (e.fallbackDepth > 0) "#${e.fallbackDepth + 1}" else "primary",
                "Transcript model" to e.model.ifBlank { "—" },
                "Language" to (e.lang ?: "—"),
                "Transcript time" to if (e.sttMs > 0) "${e.sttMs} ms" else "—",
                "Cleanup" to if (e.cleaned) "on" else "off",
                "Cleanup provider" to e.cleanupProvider.ifBlank { "—" },
                "Cleanup model" to e.cleanupModel.ifBlank { "—" },
                "Reasoning" to e.reasoning.ifBlank { "—" },
                "Cleanup time" to if (e.cleanupMs > 0) "${e.cleanupMs} ms" else "—",
                "Failure" to e.failure.ifBlank { "—" },
                "Stored audio" to if (e.recording.isNotBlank()) "yes (${e.durMs / 1000}s)" else "no",
            )
        val body =
            buildString {
                details.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
                append("\n").append(e.text)
            }
        AlertDialog
            .Builder(this)
            .setTitle("Transcription")
            .setMessage(body)
            .setPositiveButton("Copy") { _, _ -> copy(e.text) }
            .setNeutralButton("Clean up again") { _, _ -> reRunCleanup(e) }
            .setNegativeButton("Delete") { _, _ -> confirmDelete(e) }
            .show()
    }

    /** Re-sends the stored raw text through the current cleanup chain. */
    private fun reRunCleanup(e: HistoryEntry) {
        val source = e.rawText.ifBlank { e.text }
        Toast.makeText(this, "Cleaning up again…", Toast.LENGTH_SHORT).show()
        RedoCleanup.run(this, source) { outcome ->
            runOnUiThread {
                val cleaned = outcome.text
                if (cleaned.isNullOrBlank()) {
                    Toast.makeText(this, "Cleanup failed: ${SecurePrefs.redact(outcome.error)}", Toast.LENGTH_LONG).show()
                } else {
                    HistoryManager.replace(
                        this,
                        e.ts,
                        e.copy(
                            text = cleaned,
                            rawText = source,
                            cleaned = true,
                            cleanupProvider = outcome.provider,
                            cleanupModel = outcome.model,
                            reasoning = outcome.reasoning,
                            cleanupMs = outcome.elapsedMs,
                            failure = "",
                        ),
                    )
                    reload()
                    Toast.makeText(this, "Cleaned up again with ${outcome.provider}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Re-runs the audio through the current transcription chain. Only offered
     * when the recording was kept — otherwise there is nothing to re-run.
     */
    private fun reRunTranscription(e: HistoryEntry) {
        val wav = RecordingStore.read(this, e.recording)
        if (wav == null || wav.isEmpty()) {
            Toast.makeText(this, "Recording not available", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Transcribing again…", Toast.LENGTH_SHORT).show()
        RedoTranscribe.run(this, wav) { outcome ->
            runOnUiThread {
                val fresh = outcome.text
                if (fresh.isNullOrBlank()) {
                    Toast.makeText(this, "Transcription failed: ${SecurePrefs.redact(outcome.error)}", Toast.LENGTH_LONG).show()
                } else {
                    // The new text is raw again: cleanup has to be re-run to be
                    // true, so the cleanup metadata is cleared rather than kept.
                    HistoryManager.replace(
                        this,
                        e.ts,
                        e.copy(
                            text = fresh,
                            rawText = "",
                            cleaned = false,
                            lang = outcome.language ?: e.lang,
                            provider = outcome.provider.ifBlank { e.provider },
                            model = outcome.model.ifBlank { e.model },
                            fallbackDepth = 0,
                            sttMs = outcome.elapsedMs,
                            cleanupProvider = "",
                            cleanupModel = "",
                            reasoning = "",
                            cleanupMs = 0,
                            failure = "",
                        ),
                    )
                    reload()
                    Toast.makeText(this, "Re-transcribed with ${outcome.provider}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDelete(e: HistoryEntry) {
        AlertDialog
            .Builder(this)
            .setTitle("Delete entry?")
            .setMessage(e.text.take(80) + if (e.text.length > 80) "…" else "")
            .setPositiveButton("Delete") { _, _ ->
                HistoryManager.removeByTs(this, e.ts)
                reload()
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("phonewhisper", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH_PARENT = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP_CONTENT = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
