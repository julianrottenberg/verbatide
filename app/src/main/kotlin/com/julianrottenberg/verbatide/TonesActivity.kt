package com.julianrottenberg.verbatide

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Per-app tone picker (WisprFlow-style): choose which tone the cleanup pass
 * uses depending on the app you are dictating into — e.g. formal in your mail
 * client, casual in chat apps. Mappings live in ToneManager (app_tones.json).
 */
class TonesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFF7F7FA.toInt())
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                setPadding(dp(16), dp(48), dp(16), dp(16))
            }

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
                setPadding(0, 0, dp(16), 0)
                isClickable = true
                setOnClickListener { finish() }
            }
        val title =
            TextView(this).apply {
                text = "App tones"
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            }
        titleBar.addView(back)
        titleBar.addView(title)
        root.addView(titleBar)

        val subtitle =
            TextView(this).apply {
                text =
                    "Use a different writing style depending on the app you dictate into. Tap a row to change its tone, long-press to remove it."
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                setPadding(0, 0, 0, dp(12))
            }
        root.addView(subtitle)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        fun render() {
            list.removeAllViews()
            val items = ToneManager.load(this)
            if (items.isEmpty()) {
                val empty =
                    TextView(this).apply {
                        text = "Every app uses the default tone. Tap + Add to customize one."
                        textSize = 14f
                        setPadding(dp(12), dp(16), 0, 0)
                        setTextColor(0xFF777777.toInt())
                    }
                list.addView(empty)
            } else {
                for (m in items.sortedBy { it.appLabel.lowercase() }) {
                    val row =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            setBackgroundColor(0xFFFFFFFF.toInt())
                        }
                    val params = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
                    val info =
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                        }
                    val appName =
                        TextView(this).apply {
                            text = m.appLabel
                            textSize = 16f
                            setTextColor(0xFF1F1F1F.toInt())
                        }
                    val pkg =
                        TextView(this).apply {
                            text = m.packageName
                            textSize = 11f
                            setTextColor(0xFF999999.toInt())
                        }
                    info.addView(appName)
                    info.addView(pkg)
                    val toneLabel =
                        TextView(this).apply {
                            text = ToneManager.tone(m.toneKey).label
                            textSize = 14f
                            setTextColor(0xFF0066CC.toInt())
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    row.addView(info)
                    row.addView(toneLabel)
                    row.isClickable = true
                    row.setOnClickListener {
                        pickTone(m.toneKey) { tone ->
                            ToneManager.upsert(this, AppToneMapping(m.packageName, m.appLabel, tone.key))
                            render()
                        }
                    }
                    row.setOnLongClickListener {
                        AlertDialog
                            .Builder(this)
                            .setTitle("Remove tone?")
                            .setMessage("${m.appLabel} goes back to the default tone.")
                            .setPositiveButton("Remove") { _, _ ->
                                ToneManager.remove(this, m.packageName)
                                render()
                            }.setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                    list.addView(row, params)
                }
            }
        }
        render()

        val addBtn =
            TextView(this).apply {
                text = "+ Add app"
                textSize = 16f
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setTextColor(0xFF1F1F1F.toInt())
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(0xFFEEEEEE.toInt())
                gravity = Gravity.CENTER
                isClickable = true
                val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) }
                layoutParams = lp
                setOnClickListener {
                    pickApp { pkg, label ->
                        pickTone("default") { tone ->
                            ToneManager.upsert(this@TonesActivity, AppToneMapping(pkg, label, tone.key))
                            render()
                        }
                    }
                }
            }
        root.addView(addBtn)

        setContentView(root)
    }

    private fun pickTone(
        currentKey: String,
        onPicked: (ToneManager.Tone) -> Unit,
    ) {
        val tones = ToneManager.TONES
        val labels = tones.map { it.label }.toTypedArray()
        val checked = tones.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        AlertDialog
            .Builder(this)
            .setTitle("Tone")
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                onPicked(tones[which])
                dlg.dismiss()
            }.setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickApp(onPicked: (packageName: String, label: String) -> Unit) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved =
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        val apps =
            resolved
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
                .filterNot { it.first == packageName } // no point toning ourselves
                .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(this, "No launchable apps found", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { "${it.second}\n${it.first}" }.toTypedArray()
        AlertDialog
            .Builder(this)
            .setTitle("Choose app")
            .setItems(labels) { _, which -> onPicked(apps[which].first, apps[which].second) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}
