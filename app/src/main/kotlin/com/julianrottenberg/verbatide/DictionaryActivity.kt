package com.julianrottenberg.verbatide

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
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

class DictionaryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F7FA.toInt())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setPadding(dp(16), dp(48), dp(16), dp(16))
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
            setPadding(0, 0, dp(16), 0)
            isClickable = true
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Dictionary"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        titleBar.addView(back)
        titleBar.addView(title)
        root.addView(titleBar)

        val subtitle = TextView(this).apply {
            text = "Adds words/phrases the STT should recognise. Sent to providers that support a prompt hint."
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(subtitle)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        fun render() {
            list.removeAllViews()
            val items = DictionaryManager.load(this)
            if (items.isEmpty()) {
                val empty = TextView(this).apply {
                    text = "No entries. Tap + Add to create your first word."
                    textSize = 14f
                    setPadding(dp(12), dp(16), 0, 0)
                    setTextColor(0xFF777777.toInt())
                }
                list.addView(empty)
            } else {
                for (e in items) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setBackgroundColor(if (e.enabled) 0xFFFFFFFF.toInt() else 0xFFE0E0E0.toInt())
                    }
                    val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
                    val info = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    }
                    val pattern = TextView(this).apply {
                        text = e.pattern
                        textSize = 16f
                        setTextColor(0xFF1F1F1F.toInt())
                    }
                    val replace = TextView(this).apply {
                        text = if (e.replacement.isBlank() || e.replacement.equals(e.pattern, true)) "(no replacement)"
                        else "→ ${e.replacement}"
                        textSize = 13f
                        setTextColor(0xFF777777.toInt())
                    }
                    info.addView(pattern)
                    info.addView(replace)
                    row.addView(info)
                    row.isClickable = true
                    row.setOnClickListener { promptEdit(e) }
                    row.setOnLongClickListener {
                        AlertDialog.Builder(this)
                            .setTitle("Delete entry?")
                            .setMessage("\"${e.pattern}\"")
                            .setPositiveButton("Delete") { _, _ ->
                                DictionaryManager.remove(this, e.id)
                                render()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                    list.addView(row, params)
                }
            }
        }
        render()

        val addBtn = TextView(this).apply {
            text = "+ Add entry"
            textSize = 16f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setTextColor(0xFF1F1F1F.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(0xFFEEEEEE.toInt())
            gravity = Gravity.CENTER
            isClickable = true
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
            layoutParams = lp
            setOnClickListener { promptEdit(null) }
        }
        root.addView(addBtn)

        setContentView(root)
    }

    private fun promptEdit(existing: DictEntry?) {
        val patInput = EditText(this).apply {
            hint = "Word / phrase"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.pattern ?: "")
        }
        val repInput = EditText(this).apply {
            hint = "Replacement (optional)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(existing?.replacement ?: "")
        }
        val enabled = if (existing?.enabled == false) false else true
        val enabledBox = android.widget.CheckBox(this).apply {
            text = "Enabled"
            isChecked = enabled
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(TextView(this@DictionaryActivity).apply { text = "Pattern"; textSize = 12f })
            addView(patInput)
            addView(TextView(this@DictionaryActivity).apply { text = "Replacement (optional)"; textSize = 12f })
            addView(repInput)
            addView(enabledBox)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New entry" else "Edit entry")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val pat = patInput.text.toString().trim()
                if (pat.isBlank()) {
                    Toast.makeText(this, "Pattern cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val entry = DictEntry(
                    id = existing?.id ?: System.currentTimeMillis(),
                    pattern = pat,
                    replacement = repInput.text.toString().trim(),
                    enabled = enabledBox.isChecked,
                )
                DictionaryManager.upsert(this, entry)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager) // keep import used
                recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
