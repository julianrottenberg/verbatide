package com.kafkasl.phonewhisper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusSubtitle: TextView
    private lateinit var audioRowSub: TextView
    private lateinit var accRowSub: TextView
    private lateinit var keyRowSub: TextView
    private lateinit var keyRowTitle: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var promptContainer: LinearLayout
    private lateinit var providerContainer: LinearLayout
    private lateinit var customProviderContainer: LinearLayout
    private lateinit var providerSummarySub: TextView
    private lateinit var customSttUrlSub: TextView
    private lateinit var customSttModelSub: TextView
    private lateinit var customChatUrlSub: TextView
    private lateinit var customChatModelSub: TextView

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()
    private val providerRows = mutableMapOf<Provider, ProviderRowViews>()

    private data class ModelRowViews(
        val radio: MaterialRadioButton,
        val progress: LinearProgressIndicator,
        val subtitle: TextView,
        val dlBtn: MaterialButton,
    )

    private data class PromptRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView,
    )

    private data class ProviderRowViews(
        val radio: MaterialRadioButton,
        val subtitle: TextView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = vertical(0, 0)

        val header = TextView(this).apply {
            text = "Phone Whisper"
            textSize = 32f
            setPadding(dp(24), dp(64), dp(24), dp(24))
        }
        root.addView(header)

        val statusRow = settingsRow("Status", "Checking...")
        statusSubtitle = statusRow.findViewWithTag("subtitle")
        root.addView(statusRow)

        // --- Setup ---
        root.addView(sectionHeader("Setup"))

        val audioRow = settingsRow("Audio permission", "Checking...") {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }
        audioRowSub = audioRow.findViewWithTag("subtitle")
        root.addView(audioRow)

        val accRow = settingsRow("Accessibility service", "Checking...") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        accRowSub = accRow.findViewWithTag("subtitle")
        root.addView(accRow)

        // --- Engine (local vs cloud toggle) ---
        val isCloud = !prefs().getBoolean("use_local", true)

        val cloudSwitch = MaterialSwitch(this).apply {
            isChecked = isCloud
            isClickable = false
        }
        val cloudRow = settingsRow("Use cloud transcription", "On-device by default", cloudSwitch) {
            val newCloud = !cloudSwitch.isChecked
            prefs().edit().putBoolean("use_local", !newCloud).apply()
            cloudSwitch.isChecked = newCloud
            refresh()
        }
        root.addView(cloudRow)

        // --- Provider (visible only when cloud) ---
        providerContainer = vertical(0)
        providerContainer.addView(sectionHeader("Cloud provider"))
        for (provider in Provider.entries) {
            providerContainer.addView(buildProviderRow(provider))
        }
        // Inline summary row so collapsed state is still informative
        val providerSummaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        providerSummarySub = TextView(this).apply {
            tag = "provider_summary"
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
        }
        providerSummaryRow.addView(providerSummarySub)
        providerContainer.addView(providerSummaryRow)

        // Custom provider details (visible only when provider == CUSTOM and cloud is on)
        customProviderContainer = vertical(0)
        customProviderContainer.addView(sectionHeader("Custom endpoints"))
        run {
            val row = settingsRow(providerCustomSttUrlTitle(), providerCustomSttUrlSubtitle()) { promptCustomSttEndpoint() }
            customSttUrlSub = row.findViewWithTag("subtitle")
            customProviderContainer.addView(row)
        }
        run {
            val row = settingsRow(providerCustomSttModelTitle(), providerCustomSttModelSubtitle()) { promptCustomSttModel() }
            customSttModelSub = row.findViewWithTag("subtitle")
            customProviderContainer.addView(row)
        }
        run {
            val row = settingsRow(providerCustomChatUrlTitle(), providerCustomChatUrlSubtitle()) { promptCustomChatEndpoint() }
            customChatUrlSub = row.findViewWithTag("subtitle")
            customProviderContainer.addView(row)
        }
        run {
            val row = settingsRow(providerCustomChatModelTitle(), providerCustomChatModelSubtitle()) { promptCustomChatModel() }
            customChatModelSub = row.findViewWithTag("subtitle")
            customProviderContainer.addView(row)
        }
        providerContainer.addView(customProviderContainer)
        root.addView(providerContainer)

        // Local Models
        modelContainer = vertical(0)
        modelContainer.addView(sectionHeader("Local models"))
        for (m in MODEL_CATALOG) modelContainer.addView(buildModelRow(m))
        root.addView(modelContainer)

        // --- Post-Processing ---
        root.addView(sectionHeader("Post-Processing"))

        val isPostProcessing = prefs().getBoolean("use_post_processing", false)
        val postProcessSwitch = MaterialSwitch(this).apply {
            isChecked = isPostProcessing
            isClickable = false
        }
        val postProcessRow = settingsRow(
            "Cleanup transcript",
            "Fix grammar & punctuation via chat API",
            postProcessSwitch,
        ) {
            val newVal = !postProcessSwitch.isChecked
            prefs().edit().putBoolean("use_post_processing", newVal).apply()
            postProcessSwitch.isChecked = newVal
            refresh()
        }
        root.addView(postProcessRow)

        promptContainer = vertical(0)
        for (preset in promptPresets()) promptContainer.addView(buildPromptRow(preset))
        root.addView(promptContainer)

        promptRow = settingsRow("Edit current prompt", currentPrompt()) { promptPostProcessing() }
        promptRowSub = promptRow.findViewWithTag("subtitle")
        promptRowSub.maxLines = 2
        promptRowSub.ellipsize = android.text.TextUtils.TruncateAt.END
        root.addView(promptRow)

        // --- API Key ---
        root.addView(sectionHeader("API key"))

        val keyRow = settingsRow("API key", "Tap to set") { promptApiKey() }
        keyRowTitle = keyRow.findViewWithTag("title")
        keyRowSub = keyRow.findViewWithTag("subtitle")
        root.addView(keyRow)

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(attrColor(android.R.attr.colorBackground))
                addView(root)
            },
        )

        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        refresh()
    }

    // -----------------------------------------------------------------------
    // Provider rows
    // -----------------------------------------------------------------------

    private fun buildProviderRow(provider: Provider): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val defaults = ProviderConfig.defaultsFor(provider)
        val subtitle = when (provider) {
            Provider.OPENAI -> "api.openai.com · whisper-1 · gpt-4o-mini"
            Provider.GROQ -> "api.groq.com · ${defaults.sttModel} · ${defaults.chatModel}"
            Provider.OPENROUTER -> "openrouter.ai · ${defaults.sttModel} · ${defaults.chatModel}"
            Provider.CUSTOM -> "Your own OpenAI-compatible endpoint"
        }
        val row = settingsRow(provider.displayName, subtitle, radio) {
            ProviderConfig.saveProvider(prefs(), provider)
            refresh()
        }
        // Retag so we can refresh selection state
        val subtitleView = row.findViewWithTag<TextView>("subtitle")
        providerRows[provider] = ProviderRowViews(radio, subtitleView)
        refreshProviderRow(provider)
        return row
    }

    private fun refreshProviderRow(provider: Provider) {
        val views = providerRows[provider] ?: return
        views.radio.isChecked = ProviderConfig.selected(prefs()) == provider
    }

    private fun refreshProviderRows() = Provider.entries.forEach { refreshProviderRow(it) }

    private fun providerCustomSttUrlTitle() = "STT endpoint"
    private fun providerCustomSttModelTitle() = "STT model"
    private fun providerCustomChatUrlTitle() = "Chat endpoint"
    private fun providerCustomChatModelTitle() = "Chat model"

    private fun providerCustomSttUrlSubtitle(): String {
        val v = ProviderConfig.customSttUrl(prefs())
        return if (v.isBlank()) "Tap to set (e.g. https://api.example.com/v1/audio/transcriptions)" else v
    }

    private fun providerCustomSttModelSubtitle(): String {
        val v = ProviderConfig.customSttModel(prefs())
        return if (v.isBlank()) "Tap to set (e.g. whisper-large-v3)" else v
    }

    private fun providerCustomChatUrlSubtitle(): String {
        val v = ProviderConfig.customChatUrl(prefs())
        return if (v.isBlank()) "Tap to set (e.g. https://api.example.com/v1/chat/completions)" else v
    }

    private fun providerCustomChatModelSubtitle(): String {
        val v = ProviderConfig.customChatModel(prefs())
        return if (v.isBlank()) "Tap to set (e.g. gpt-4o-mini)" else v
    }

    // -----------------------------------------------------------------------
    // Model rows
    // -----------------------------------------------------------------------

    private fun buildModelRow(model: Model): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val dlBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            text = "↓"
            textSize = 18f
            setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        }

        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP_MATCH, dp(4)).apply { topMargin = dp(8) }
        }

        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dlBtn)
            addView(radio)
        }

        val row = settingsRow(
            model.name,
            "${model.quality} · ${model.sizeMb} MB",
            rightContainer,
        ) { onModelAction(model) }

        val textContainer = row.getChildAt(0) as LinearLayout
        textContainer.addView(progress)

        modelRows[model.archive] = ModelRowViews(radio, progress, textContainer.findViewWithTag("subtitle"), dlBtn)
        refreshCard(model)

        return row
    }

    private fun onModelAction(model: Model) {
        val views = modelRows[model.archive] ?: return

        if (ModelDownloader.isInstalled(this, model)) {
            selectModel(model.archive)
            return
        }

        views.dlBtn.isEnabled = false
        views.progress.visibility = View.VISIBLE
        views.progress.isIndeterminate = false
        views.subtitle.text = "Starting download..."

        ModelDownloader.download(this, model) { state ->
            runOnUiThread {
                when (state) {
                    is DownloadState.Downloading -> {
                        views.progress.progress = (state.progress * 100).toInt()
                        views.subtitle.text = "Downloading: ${(state.progress * 100).toInt()}%"
                    }

                    is DownloadState.Extracting -> {
                        views.progress.isIndeterminate = true
                        views.subtitle.text = "Extracting..."
                    }

                    is DownloadState.Done -> {
                        views.progress.visibility = View.GONE
                        selectModel(model.archive)
                        toast("${model.name} ready!")
                    }

                    is DownloadState.Error -> {
                        views.progress.visibility = View.GONE
                        views.subtitle.text = "Error: ${state.message}"
                        views.dlBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private fun selectModel(archive: String) {
        prefs().edit().putString("model_name", archive).apply()
        WhisperAccessibilityService.instance?.reloadModel()
        refreshAllCards()
        refresh()
    }

    private fun refreshCard(model: Model) {
        val views = modelRows[model.archive] ?: return
        val active = prefs().getString("model_name", "") == model.archive
        val installed = ModelDownloader.isInstalled(this, model)

        views.radio.isChecked = active
        views.radio.visibility = if (installed) View.VISIBLE else View.GONE
        views.dlBtn.visibility = if (installed) View.GONE else View.VISIBLE

        if (views.progress.visibility == View.GONE) {
            views.subtitle.text = "${model.quality} · ${model.sizeMb} MB"
        }
    }

    private fun refreshAllCards() = MODEL_CATALOG.forEach { refreshCard(it) }

    // -----------------------------------------------------------------------
    // Prompt rows
    // -----------------------------------------------------------------------

    private fun buildPromptRow(preset: PromptPreset): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }

        val row = settingsRow(preset.title, preset.subtitle, radio) { selectPrompt(preset.key) }

        promptRows[preset.key] = PromptRowViews(radio, row.findViewWithTag("subtitle"))
        refreshPromptRow(preset)
        return row
    }

    private fun selectPrompt(key: String) {
        val prompt = when (key) {
            "custom" -> customPrompt()
            else -> promptPresets().firstOrNull { it.key == key }?.prompt
        } ?: return
        prefs().edit().putString("post_processing_prompt", prompt).apply()
        refreshPromptRows()
        refresh()
    }

    private fun refreshPromptRow(preset: PromptPreset) {
        val views = promptRows[preset.key] ?: return
        val current = currentPrompt()
        val active = when (preset.key) {
            "custom" -> current != PostProcessor.DEV_PROMPT && current != PostProcessor.SIMPLE_PROMPT
            else -> current == preset.prompt
        }
        views.radio.isChecked = active
        views.subtitle.text = if (preset.key == "custom") customPromptSummary() else preset.subtitle
    }

    private fun refreshPromptRows() = promptPresets().forEach { refreshPromptRow(it) }

    // -----------------------------------------------------------------------
    // Refresh
    // -----------------------------------------------------------------------

    private fun refresh() {
        val audio = hasPerm(Manifest.permission.RECORD_AUDIO)
        val acc = WhisperAccessibilityService.instance != null
        val useLocal = prefs().getBoolean("use_local", true)
        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val hasKey = !prefs().getString("api_key", "").isNullOrBlank()
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()
        val selectedProvider = ProviderConfig.selected(prefs())
        val isCustomProvider = selectedProvider == Provider.CUSTOM

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        // Visibility
        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        providerContainer.visibility = if (!useLocal) View.VISIBLE else View.GONE
        customProviderContainer.visibility = if (!useLocal && isCustomProvider) View.VISIBLE else View.GONE
        promptContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE

        // Provider summary + radio states
        providerSummarySub.text = ProviderConfig.summary(prefs())
        refreshProviderRows()

        customSttUrlSub.text = providerCustomSttUrlSubtitle()
        customSttModelSub.text = providerCustomSttModelSubtitle()
        customChatUrlSub.text = providerCustomChatUrlSubtitle()
        customChatModelSub.text = providerCustomChatModelSubtitle()

        // API key row — title reflects provider
        keyRowTitle.text = when (selectedProvider) {
            Provider.GROQ -> "Groq API key"
            Provider.OPENROUTER -> "OpenRouter API key"
            Provider.CUSTOM -> "API key"
            else -> "OpenAI API key"
        }
        val apiKey = prefs().getString("api_key", "") ?: ""
        keyRowSub.text = when {
            apiKey.isBlank() -> "Tap to set"
            apiKey.length > 7 -> "${apiKey.take(3)}...${apiKey.takeLast(4)}"
            else -> "***"
        }

        promptRowSub.text = currentPrompt()

        val cur = prefs().getString("model_name", "") ?: ""
        if (cur.isBlank() || !File(filesDir, "models/$cur").exists()) {
            MODEL_CATALOG.firstOrNull { ModelDownloader.isInstalled(this, it) }
                ?.let { selectModel(it.archive) }
        }

        val localReady = useLocal && hasModel
        val cloudReady = !useLocal && hasKey
        val postReady = !usePostProcessing || hasKey
        val ready = audio && acc && (localReady || cloudReady) && postReady

        statusSubtitle.text = if (ready) "Ready — tap the overlay dot to dictate" else "Setup required"
        statusSubtitle.setTextColor(
            if (ready) attrColor(com.google.android.material.R.attr.colorPrimary)
            else attrColor(android.R.attr.textColorSecondary),
        )

        refreshAllCards()
        refreshPromptRows()
    }

    // -----------------------------------------------------------------------
    // Dialogs
    // -----------------------------------------------------------------------

    private fun promptApiKey() {
        val provider = ProviderConfig.selected(prefs())
        val hint = when (provider) {
            Provider.GROQ -> "gsk_..."
            Provider.OPENROUTER -> "sk-or-..."
            else -> "sk-..."
        }
        val input = EditText(this).apply {
            this.hint = hint
            setText(prefs().getString("api_key", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("${provider.displayName} API key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                prefs().edit().putString("api_key", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomSttEndpoint() {
        val input = EditText(this).apply {
            hint = "https://api.example.com/v1/audio/transcriptions"
            setText(ProviderConfig.customSttUrl(prefs()))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom STT endpoint")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotBlank() && !ProviderConfig.isValidCustomUrl(raw)) {
                    toast("Invalid URL — must be http(s)://…")
                    return@setPositiveButton
                }
                if (raw.isNotBlank() && raw.startsWith("http://") && !ProviderConfig.isLoopbackHttp(raw)) {
                    toast("http:// only allowed for localhost — use https://")
                    return@setPositiveButton
                }
                ProviderConfig.saveCustom(prefs(), sttUrl = raw)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomSttModel() {
        val input = EditText(this).apply {
            hint = "whisper-large-v3"
            setText(ProviderConfig.customSttModel(prefs()))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom STT model")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                ProviderConfig.saveCustom(prefs(), sttModel = input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomChatEndpoint() {
        val input = EditText(this).apply {
            hint = "https://api.example.com/v1/chat/completions"
            setText(ProviderConfig.customChatUrl(prefs()))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom chat endpoint")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isNotBlank() && !ProviderConfig.isValidCustomUrl(raw)) {
                    toast("Invalid URL — must be http(s)://…")
                    return@setPositiveButton
                }
                if (raw.isNotBlank() && raw.startsWith("http://") && !ProviderConfig.isLoopbackHttp(raw)) {
                    toast("http:// only allowed for localhost — use https://")
                    return@setPositiveButton
                }
                ProviderConfig.saveCustom(prefs(), chatUrl = raw)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomChatModel() {
        val input = EditText(this).apply {
            hint = "llama-3.3-70b-versatile"
            setText(ProviderConfig.customChatModel(prefs()))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom chat model")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                ProviderConfig.saveCustom(prefs(), chatModel = input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptPostProcessing() {
        val input = EditText(this).apply {
            hint = "Prompt"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(currentPrompt())
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Edit current prompt")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                val finalPrompt = if (text.isBlank()) PostProcessor.DEFAULT_PROMPT else text
                prefs().edit()
                    .putString("custom_post_processing_prompt", finalPrompt)
                    .putString("post_processing_prompt", finalPrompt)
                    .apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    private fun settingsRow(
        title: String,
        subtitle: String,
        widget: View? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = onClick != null
            isFocusable = onClick != null
            if (onClick != null) {
                val outValue = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setOnClickListener { onClick() }
            }
        }

        val textContainer = vertical(0).apply {
            layoutParams = LinearLayout.LayoutParams(0, LP_WRAP, 1f)
        }

        textContainer.addView(
            TextView(this).apply {
                tag = "title"
                text = title
                textSize = 18f
                setTextColor(attrColor(android.R.attr.textColorPrimary))
            },
        )

        textContainer.addView(
            TextView(this).apply {
                tag = "subtitle"
                text = subtitle
                textSize = 14f
                setTextColor(attrColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(2), 0, 0)
            },
        )

        row.addView(textContainer)
        if (widget != null) row.addView(widget)

        return row
    }

    private fun sectionHeader(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
        setPadding(dp(24), dp(24), dp(24), dp(8))
    }

    private fun vertical(padH: Int, padV: Int = padH) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padH, padV, padH, padV)
    }

    private fun currentPrompt() = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
    private fun customPrompt() = prefs().getString("custom_post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

    private fun customPromptSummary(): String {
        val prompt = customPrompt()
        return if (prompt == PostProcessor.DEFAULT_PROMPT) "Your edited prompt"
        else prompt.replace("\n", " ")
    }

    private data class PromptPreset(val key: String, val title: String, val subtitle: String, val prompt: String)

    private fun promptPresets() = listOf(
        PromptPreset(
            key = "dev",
            title = "Dev cleanup",
            subtitle = "Best for coding, CLI, and project names",
            prompt = PostProcessor.DEV_PROMPT,
        ),
        PromptPreset(
            key = "simple",
            title = "Simple cleanup",
            subtitle = "Grammar, punctuation, and light cleanup",
            prompt = PostProcessor.SIMPLE_PROMPT,
        ),
        PromptPreset(
            key = "custom",
            title = "Custom",
            subtitle = customPromptSummary(),
            prompt = customPrompt(),
        ),
    )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
