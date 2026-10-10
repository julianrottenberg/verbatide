package com.julianrottenberg.verbatide

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * Tone-centric UI: the list shows tones (what they do, which apps use them),
 * a tone's detail screen edits its instruction and manages its apps, and the
 * app picker is a full-screen multi-select with icons and real app names.
 */
class TonesActivity : AppCompatActivity() {
    private data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
    )

    private enum class Screen { LIST, DETAIL, PICKER }

    private lateinit var container: FrameLayout
    private val loader = Executors.newSingleThreadExecutor()
    private val iconCache = HashMap<String, Drawable?>()

    @Volatile private var appEntries: List<AppEntry>? = null

    private var screen = Screen.LIST
    private var detailToneKey = ToneManager.TONES[0].key
    private val pickerSelection = LinkedHashSet<String>()
    private var pickerQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
        loadApps()
        render()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            Screen.PICKER -> {
                showDetail(detailToneKey)
            }

            Screen.DETAIL -> {
                showList()
            }

            Screen.LIST -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    // ---------- screens ----------

    private fun render() {
        when (screen) {
            Screen.LIST -> showList()
            Screen.DETAIL -> showDetail(detailToneKey)
            Screen.PICKER -> showPicker()
        }
    }

    private fun showList() {
        screen = Screen.LIST
        val mappings = ToneManager.mappings(this)
        val tones = ToneManager.effectiveTones(this)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tones.forEach { tone ->
            val assigned = mappings.filter { it.toneKey == tone.key }
            list.addView(toneRow(tone, assigned))
            list.addView(divider())
        }

        val scroll = ScrollView(this).apply { addView(list) }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(attrColor(android.R.attr.colorBackground))
                addView(header("App tones", actionLabel = "+", onAction = { showCreateToneDialog() }), layoutWidth())
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(
                    footer("Apps without an assigned tone use Default."),
                    layoutWidth(),
                )
            }
        setContent(root)
    }

    private fun showDetail(toneKey: String) {
        screen = Screen.DETAIL
        detailToneKey = toneKey
        val tone = ToneManager.effectiveTones(this).firstOrNull { it.key == toneKey } ?: return showList()
        val mappings = ToneManager.mappings(this).filter { it.toneKey == tone.key }
        val isDefault = tone.key == ToneManager.TONES[0].key

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        body.addView(
            text(tone.label, 22f, bold = true).apply {
                setPadding(dp(16), dp(8), dp(16), dp(2))
            },
            layoutWidth(),
        )
        body.addView(
            text(tone.description, 14f, color = attrColor(android.R.attr.textColorSecondary)).apply {
                setPadding(dp(16), 0, dp(16), dp(12))
            },
            layoutWidth(),
        )

        if (isDefault) {
            body.addView(
                text(
                    "Default is the fallback for every app without an assigned tone. " +
                        "It adds no style instruction, so the cleanup model only fixes grammar and punctuation.",
                    14f,
                ).apply { setPadding(dp(16), 0, dp(16), dp(12)) },
                layoutWidth(),
            )
        } else {
            body.addView(sectionLabel("Instruction sent to the model"))
            val editor =
                EditText(this).apply {
                    setText(tone.instruction)
                    minLines = 3
                    gravity = Gravity.TOP
                    textSize = 14f
                    setTextColor(attrColor(android.R.attr.textColorPrimary))
                    setHintTextColor(attrColor(android.R.attr.textColorSecondary))
                    hint = "e.g. Rewrite the text in a formal register."
                }
            body.addView(editor, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            buttons.addView(
                MaterialButton(this).apply {
                    text = "Save"
                    setOnClickListener {
                        ToneManager.setInstructionOverride(this@TonesActivity, tone.key, editor.text.toString())
                        showDetail(tone.key)
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) },
            )
            val builtIn = ToneManager.TONES.firstOrNull { it.key == tone.key }
            if (builtIn != null && builtIn.instruction != tone.instruction) {
                buttons.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "Reset to default"
                        setOnClickListener {
                            ToneManager.resetInstruction(this@TonesActivity, tone.key)
                            showDetail(tone.key)
                        }
                    },
                )
            }
            if (tone.isCustom) {
                buttons.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "Delete tone"
                        setTextColor(0xFFE53935.toInt())
                        setOnClickListener { confirmDeleteTone(tone) }
                    },
                )
            }
            body.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })

            body.addView(sectionLabel("Assigned apps"))
            if (mappings.isEmpty()) {
                body.addView(
                    text("No apps assigned yet.", 14f, color = attrColor(android.R.attr.textColorSecondary)).apply {
                        setPadding(dp(16), 0, dp(16), dp(8))
                    },
                    layoutWidth(),
                )
            }
            mappings.forEach { mapping ->
                body.addView(assignedAppRow(mapping))
            }
            body.addView(
                MaterialButton(this).apply {
                    text = "Add apps"
                    setOnClickListener { openPicker(tone.key, mappings.map { it.packageName }) }
                },
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) },
            )
        }

        val scroll = ScrollView(this).apply { addView(body) }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(attrColor(android.R.attr.colorBackground))
                addView(header(tone.label, onBack = { showList() }), layoutWidth())
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        setContent(root)
    }

    private fun showPicker() {
        screen = Screen.PICKER
        val tone = ToneManager.effectiveTones(this).firstOrNull { it.key == detailToneKey } ?: return showList()
        val entries = appEntries

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val search =
            EditText(this).apply {
                hint = "Search apps"
                setText(pickerQuery)
                setTextColor(attrColor(android.R.attr.textColorPrimary))
                setHintTextColor(attrColor(android.R.attr.textColorSecondary))
                addTextChangedListener(
                    object : TextWatcher {
                        override fun afterTextChanged(s: Editable?) {
                            pickerQuery = s?.toString().orEmpty()
                            showPicker()
                        }

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
                        ) {}
                    },
                )
            }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(4), dp(16), dp(8)) })

        if (entries == null) {
            body.addView(
                ProgressBar(this).apply { isIndeterminate = true },
                LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(32)
                },
            )
        } else {
            val mappings = ToneManager.mappings(this).associateBy { it.packageName }
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            entries
                .filter { pickerQuery.isBlank() || it.label.contains(pickerQuery, ignoreCase = true) }
                .forEach { entry ->
                    list.addView(pickerRow(entry, mappings[entry.packageName], tone))
                    list.addView(divider())
                }
            body.addView(
                ScrollView(this).apply { addView(list) },
                LinearLayout.LayoutParams(-1, 0, 1f),
            )
        }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(attrColor(android.R.attr.colorBackground))
                addView(
                    header(
                        "Apps for ${tone.label}",
                        actionLabel = "Done",
                        onAction = { applyPicker(tone) },
                        onBack = { showDetail(tone.key) },
                    ),
                    layoutWidth(),
                )
                addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        setContent(root)
    }

    // ---------- rows ----------

    private fun toneRow(
        tone: Tone,
        assigned: List<AppToneMapping>,
    ): View {
        val texts =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(tone.label, 17f, bold = true), layoutWidth())
                addView(text(tone.description, 13f, color = attrColor(android.R.attr.textColorSecondary)), layoutWidth())
            }

        val icons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                assigned.take(5).forEach { mapping ->
                    addView(
                        ImageView(this@TonesActivity).apply {
                            setImageDrawable(iconFor(mapping.packageName))
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        },
                        LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(4) },
                    )
                }
                if (assigned.size > 5) {
                    addView(text("+${assigned.size - 5}", 12f, color = attrColor(android.R.attr.textColorSecondary)))
                }
            }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundResource(rippleRes())
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icons)
            setOnClickListener { showDetail(tone.key) }
        }
    }

    private fun assignedAppRow(mapping: AppToneMapping): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
            addView(
                ImageView(this@TonesActivity).apply {
                    setImageDrawable(iconFor(mapping.packageName))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) },
            )
            addView(text(mapping.appLabel, 15f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(
                text("✕", 16f, color = attrColor(android.R.attr.textColorSecondary)).apply {
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                    setOnClickListener {
                        ToneManager.remove(this@TonesActivity, mapping.packageName)
                        showDetail(detailToneKey)
                    }
                },
            )
        }

    private fun pickerRow(
        entry: AppEntry,
        currentMapping: AppToneMapping?,
        tone: Tone,
    ): View {
        val box =
            CheckBox(this).apply {
                isChecked = entry.packageName in pickerSelection
            }
        val secondary =
            if (currentMapping != null && currentMapping.toneKey != tone.key) {
                val other = ToneManager.effectiveTones(this).firstOrNull { it.key == currentMapping.toneKey }?.label
                if (other != null) "Currently: $other" else null
            } else {
                null
            }
        val labels =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(entry.label, 15f), layoutWidth())
                if (secondary != null) {
                    addView(text(secondary, 12f, color = attrColor(android.R.attr.textColorSecondary)), layoutWidth())
                }
            }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(16), dp(4))
            setBackgroundResource(rippleRes())
            addView(box)
            addView(
                ImageView(this@TonesActivity).apply {
                    setImageDrawable(entry.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) },
            )
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener {
                if (entry.packageName in
                    pickerSelection
                ) {
                    pickerSelection.remove(entry.packageName)
                } else {
                    pickerSelection.add(entry.packageName)
                }
                box.isChecked = entry.packageName in pickerSelection
            }
        }
    }

    // ---------- picker plumbing ----------

    private fun openPicker(
        toneKey: String,
        alreadyAssigned: List<String>,
    ) {
        detailToneKey = toneKey
        pickerSelection.clear()
        pickerSelection.addAll(alreadyAssigned)
        pickerQuery = ""
        showPicker()
    }

    private fun applyPicker(tone: Tone) {
        val entries = appEntries.orEmpty()
        val before =
            ToneManager
                .mappings(this)
                .filter { it.toneKey == tone.key }
                .map { it.packageName }
                .toSet()
        entries.filter { it.packageName in pickerSelection }.forEach {
            ToneManager.upsert(this, AppToneMapping(it.packageName, it.label, tone.key))
        }
        (before - pickerSelection).forEach { ToneManager.remove(this, it) }
        showDetail(tone.key)
    }

    @Suppress("DEPRECATION")
    private fun loadApps() {
        loader.execute {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val entries =
                pm
                    .queryIntentActivities(intent, 0)
                    .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase() }
            entries.forEach { iconCache[it.packageName] = it.icon }
            appEntries = entries
            runOnUiThread { if (screen == Screen.PICKER) showPicker() }
        }
    }

    private fun iconFor(packageName: String): Drawable? {
        if (!iconCache.containsKey(packageName)) {
            iconCache[packageName] =
                runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
        return iconCache[packageName]
    }

    // ---------- dialogs ----------

    private fun showCreateToneDialog() {
        val name =
            EditText(this).apply {
                hint = "Name, e.g. Sarcastic"
            }
        val instruction =
            EditText(this).apply {
                hint = "Instruction, e.g. Rewrite the text dripping with sarcasm."
                minLines = 2
                gravity = Gravity.TOP
            }
        val fields =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(name, layoutWidth())
                addView(instruction, layoutWidth())
            }
        MaterialAlertDialogBuilder(this)
            .setTitle("New tone")
            .setView(fields)
            .setPositiveButton("Create") { _, _ ->
                val label = name.text.toString().trim()
                val instr = instruction.text.toString().trim()
                if (label.isNotBlank() && instr.isNotBlank()) {
                    val tone = ToneManager.addCustomTone(this, label, instr)
                    showDetail(tone.key)
                }
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteTone(tone: Tone) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete ${tone.label}?")
            .setMessage("Apps assigned to it fall back to Default.")
            .setPositiveButton("Delete") { _, _ ->
                ToneManager.deleteCustomTone(this, tone.key)
                showList()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- view helpers ----------

    private fun setContent(view: View) {
        container.removeAllViews()
        container.addView(view)
    }

    private fun header(
        title: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        onBack: (() -> Unit)? = null,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
            addView(
                text("←", 22f).apply {
                    setPadding(dp(12), dp(4), dp(12), dp(4))
                    setBackgroundResource(rippleRes())
                    setOnClickListener { onBack?.invoke() ?: finish() }
                },
            )
            addView(text(title, 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            if (actionLabel != null) {
                addView(
                    text(actionLabel, 18f, bold = true, color = attrColor(android.R.attr.colorPrimary)).apply {
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        setBackgroundResource(rippleRes())
                        setOnClickListener { onAction?.invoke() }
                    },
                )
            }
        }

    private fun sectionLabel(label: String): View =
        text(label, 13f, bold = true, color = attrColor(android.R.attr.textColorSecondary)).apply {
            setPadding(dp(16), dp(16), dp(16), dp(6))
        }

    private fun footer(label: String): View =
        text(label, 12f, color = attrColor(android.R.attr.textColorSecondary)).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }

    private fun text(
        value: String,
        sizeSp: Float,
        bold: Boolean = false,
        color: Int = attrColor(android.R.attr.textColorPrimary),
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun divider(): View =
        View(this)
            .apply {
                setBackgroundColor(attrColor(android.R.attr.listDivider))
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(16) }
            }

    private fun layoutWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)

    private fun attrColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun rippleRes(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
