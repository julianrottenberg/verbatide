package com.julianrottenberg.verbatide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
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
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }
        val back = TextView(this).apply {
            text = "←  Back"
            textSize = 16f
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            setPadding(0, 0, dp(16), 0)
            isClickable = true
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
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

        // Search box
        val search = EditText(this).apply {
            hint = "Search history…"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setSingleLine()
        }
        list.addView(search)

        val entries = HistoryManager.load(this).sortedByDescending { it.ts }
        val filtered = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(filtered)

        val df = SimpleDateFormat("MMM dd · HH:mm", Locale.getDefault())
        fun render(filter: String = "") {
            filtered.removeAllViews()
            val items = entries.filter { filter.isBlank() || it.text.contains(filter, true) }
            if (items.isEmpty()) {
                filtered.addView(TextView(this@HistoryActivity).apply {
                    text = if (entries.isEmpty()) "No transcriptions yet." else "No matches."
                    textSize = 14f
                    setPadding(dp(12), dp(16), 0, 0)
                    setTextColor(attrColor(android.R.attr.textColorSecondary))
                })
                return
            }
            for (e in items) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
                }
                val meta = TextView(this).apply {
                    text = "${df.format(Date(e.ts))} · ${e.provider} ${e.lang?.let { "· $it" } ?: ""}"
                    textSize = 12f
                    setTextColor(attrColor(android.R.attr.textColorSecondary))
                }
                val body = TextView(this).apply {
                    text = e.text
                    textSize = 16f
                    setTextColor(attrColor(android.R.attr.textColorPrimary))
                    ellipsize = TextUtils.TruncateAt.END
                    maxLines = 6
                    setPadding(0, dp(6), 0, dp(6))
                }
                card.addView(meta)
                card.addView(body)

                // Copy on tap
                card.isClickable = true
                card.setOnClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("phonewhisper", e.text))
                    Toast.makeText(this@HistoryActivity, "Copied", Toast.LENGTH_SHORT).show()
                }
                // Long-press to delete
                card.setOnLongClickListener {
                    AlertDialog.Builder(this@HistoryActivity)
                        .setTitle("Delete entry?")
                        .setMessage(e.text.take(80) + if (e.text.length > 80) "…" else "")
                        .setPositiveButton("Delete") { _, _ ->
                            HistoryManager.removeByTs(this@HistoryActivity, e.ts)
                            render(search.text.toString().trim())
                            Toast.makeText(this@HistoryActivity, "Deleted", Toast.LENGTH_SHORT).show()
                            true
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }

                val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
                filtered.addView(card, params)
            }
        }
        render()

        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        setContentView(root)
    }

    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
