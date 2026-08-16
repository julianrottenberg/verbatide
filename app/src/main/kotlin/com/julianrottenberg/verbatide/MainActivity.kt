package com.julianrottenberg.verbatide

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
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
    // Legacy single-key row — hidden since v0.6.0, kept for refresh() compat.
    private lateinit var keyRowSub: TextView
    private lateinit var keyRowTitle: TextView
    private lateinit var promptRowSub: TextView
    private lateinit var promptRow: LinearLayout
    private lateinit var modelContainer: LinearLayout
    private lateinit var promptContainer: LinearLayout
    private lateinit var providerContainer: LinearLayout
    private lateinit var customProviderContainer: LinearLayout
    private lateinit var providerSummarySub: TextView
    private lateinit var chatProviderContainer: LinearLayout
    private lateinit var chatCustomContainer: LinearLayout
    private lateinit var chatSummarySub: TextView
    private lateinit var sttKeyRowTitle: TextView
    private lateinit var sttKeyRowSub: TextView
    private lateinit var chatKeyRowTitle: TextView
    private lateinit var chatKeyRowSub: TextView
    private lateinit var sttKeyRow: LinearLayout
    private lateinit var chatKeyRow: LinearLayout
    private lateinit var customSttUrlSub: TextView
    private lateinit var customSttModelSub: TextView
    private lateinit var customChatUrlSub: TextView
    private lateinit var customChatModelSub: TextView
    private lateinit var reasoningRowSub: TextView
    private lateinit var reasoningRow: LinearLayout
    private lateinit var languageRow: LinearLayout
    private lateinit var languageRowSub: TextView

    // SAF launchers for settings backup/restore. Must be field initializers so
    // they register before the activity is created.
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            uri?.let { writeBackup(it) }
        }
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { readBackup(it) }
        }
    private val historyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* refresh on return */ }
    private val dictionaryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* refresh on return */ }

    private val modelRows = mutableMapOf<String, ModelRowViews>()
    private val promptRows = mutableMapOf<String, PromptRowViews>()
    private val providerRows = mutableMapOf<Pair<Provider, Boolean>, ProviderRowViews>() // (provider, isStt) -> views

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

        // --- Transcription language (cloud only; Whisper auto-translates to
        // English without an explicit language param, so this matters for
        // non-English users) ---
        languageRow = settingsRow("Transcription language", currentLanguageLabel()) { promptLanguage() }
        languageRowSub = languageRow.findViewWithTag("subtitle")
        root.addView(languageRow)

        // --- STT provider (visible only when cloud) ---
        providerContainer = vertical(0)
        providerContainer.addView(sectionHeader("Transcription provider"))
        for (provider in ProviderConfig.sttProviders) {
            providerContainer.addView(buildProviderRow(provider, isStt = true))
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

        // Custom STT details (visible only when STT provider == CUSTOM and cloud is on)
        customProviderContainer = vertical(0)
        customProviderContainer.addView(sectionHeader("Custom STT"))
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
        providerContainer.addView(customProviderContainer)
        // Model override — prefilled with the provider default (now voxtral-mini-2602 for Mistral), user can change it
        run {
            val row = settingsRow("STT model", sttModelDisplay()) { promptSttModelOverride() }
            row.tag = "sttModelOverrideRow"
            providerContainer.addView(row)
        }
        // STT API key (cloud only)
        sttKeyRow = settingsRow("STT API key", "Tap to set") { promptApiKey(isStt = true) }
        sttKeyRowTitle = sttKeyRow.findViewWithTag("title")
        sttKeyRowSub = sttKeyRow.findViewWithTag("subtitle")
        providerContainer.addView(sttKeyRow)
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

        reasoningRow = settingsRow("Reasoning effort", currentReasoningLabel()) { promptReasoning() }
        reasoningRowSub = reasoningRow.findViewWithTag("subtitle")
        root.addView(reasoningRow)

        // --- Cleanup provider (visible when post-processing is on — split so
        // STT and chat can be mixed, e.g. fal.ai for STT + Together for cleanup) ---
        chatProviderContainer = vertical(0)
        chatProviderContainer.addView(sectionHeader("Cleanup provider"))
        for (provider in ProviderConfig.chatProviders) {
            chatProviderContainer.addView(buildProviderRow(provider, isStt = false))
        }
        val chatSummaryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }
        chatSummarySub = TextView(this).apply {
            tag = "chat_summary"
            textSize = 12f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
        }
        chatSummaryRow.addView(chatSummarySub)
        chatProviderContainer.addView(chatSummaryRow)

        chatCustomContainer = vertical(0)
        chatCustomContainer.addView(sectionHeader("Custom cleanup"))
        run {
            val row = settingsRow(providerCustomChatUrlTitle(), providerCustomChatUrlSubtitle()) { promptCustomChatEndpoint() }
            customChatUrlSub = row.findViewWithTag("subtitle")
            chatCustomContainer.addView(row)
        }
        run {
            val row = settingsRow(providerCustomChatModelTitle(), providerCustomChatModelSubtitle()) { promptCustomChatModel() }
            customChatModelSub = row.findViewWithTag("subtitle")
            chatCustomContainer.addView(row)
        }
        chatProviderContainer.addView(chatCustomContainer)
        run {
            val row = settingsRow("Chat model", chatModelDisplay()) { promptChatModelOverride() }
            row.tag = "chatModelOverrideRow"
            chatProviderContainer.addView(row)
        }

        chatKeyRow = settingsRow("Cleanup API key", "Tap to set") { promptApiKey(isStt = false) }
        chatKeyRowTitle = chatKeyRow.findViewWithTag("title")
        chatKeyRowSub = chatKeyRow.findViewWithTag("subtitle")
        chatProviderContainer.addView(chatKeyRow)
        root.addView(chatProviderContainer)

        // --- Backup & restore ---
        root.addView(sectionHeader("Backup & restore"))
        root.addView(settingsRow("Export settings", "Save endpoints, prompts & preferences to a file") {
            exportLauncher.launch("phone-whisper-backup.json")
        })
        root.addView(settingsRow("Import settings", "Restore from a previously exported file") {
            importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
        })

        // --- Transcription history ---
        root.addView(buildHistorySection())

        // Hidden: legacy single "API key" row lives inside its provider
        // groups since v0.6.0 — keep the field initialised for refresh().
        run {
            val keyRow = settingsRow("API key", "Tap to set") { promptApiKey(isStt = true) }
            keyRow.visibility = View.GONE
            keyRowTitle = keyRow.findViewWithTag("title")
            keyRowSub = keyRow.findViewWithTag("subtitle")
        }

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

    private fun buildProviderRow(provider: Provider, isStt: Boolean = true): View {
        val radio = MaterialRadioButton(this).apply {
            isClickable = false
            buttonTintList = ColorStateList.valueOf(attrColor(com.google.android.material.R.attr.colorPrimary))
        }
        val defaults = ProviderConfig.defaultsFor(provider)
        val subtitle = when (provider) {
            Provider.OPENAI -> "api.openai.com · whisper-1 · gpt-4o-mini"
            Provider.GROQ -> "api.groq.com · ${defaults.sttModel} · ${defaults.chatModel}"
            Provider.OPENROUTER -> "openrouter.ai · ${defaults.sttModel} · ${defaults.chatModel}"
            Provider.TOGETHER -> "api.together.ai · ${defaults.sttModel} · ${defaults.chatModel}"
            Provider.VENICE -> "api.venice.ai · ${defaults.sttModel} · ${defaults.chatModel}"
            Provider.MISTRAL -> "api.mistral.ai · voxtral-mini-latest · mistral-small"
            Provider.NANOGPT -> "api.nano-gpt.com · Whisper-Large-V3 · gpt-4o-mini"
            Provider.FAL -> "queue.fal.run · wizper (STT only)"
            Provider.CUSTOM -> if (isStt) "Your own STT endpoint" else "Your own chat endpoint"
        }
        val row = settingsRow(provider.displayName, subtitle, radio) {
            if (isStt) ProviderConfig.saveSttProvider(prefs(), provider)
            else ProviderConfig.saveChatProvider(prefs(), provider)
            refresh()
        }
        // Retag so we can refresh selection state — key on (provider, side).
        val subtitleView = row.findViewWithTag<TextView>("subtitle")
        providerRows[provider to isStt] = ProviderRowViews(radio, subtitleView)
        refreshProviderRow(provider, isStt)
        return row
    }

    private fun refreshProviderRow(provider: Provider, isStt: Boolean = true) {
        val views = providerRows[provider to isStt] ?: return
        views.radio.isChecked = if (isStt) ProviderConfig.selectedStt(prefs()) == provider
                                else ProviderConfig.selectedChat(prefs()) == provider
    }

    private fun refreshProviderRows() {
        ProviderConfig.sttProviders.forEach { refreshProviderRow(it, true) }
        ProviderConfig.chatProviders.forEach { refreshProviderRow(it, false) }
    }

    private fun providerCustomSttUrlTitle() = "STT endpoint"
    private fun providerCustomSttModelTitle() = "STT model"
    private fun providerCustomChatUrlTitle() = "Chat endpoint"
    private fun providerCustomChatModelTitle() = "Chat model"

    private fun providerCustomSttUrlSubtitle(): String {
        val v = ProviderConfig.customSttUrl(prefs())
        return if (v.isBlank()) "Tap to set (e.g. https://api.example.com/v1/audio/transcriptions)" else v
    }

    // Prefilled defaults — let user override the model without switching to Custom.
    // sttModel(prefs) prefers custom_stt_model when set, otherwise the provider default (now voxtral-mini-2602 for Mistral).
    private fun dictionarySummary(): String {
        val n = DictionaryManager.load(this).size
        val on = if (DictionaryManager.enabled(prefs())) "on" else "off"
        return "$n entries · $on — tap to manage"
    }

    private fun sttModelDisplay(): String {
        val cur = ProviderConfig.sttModel(prefs())
        val def = ProviderConfig.defaultsFor(ProviderConfig.selectedStt(prefs())).sttModel
        return if (cur == def) "Default ($def) — tap to change" else "$cur — tap to change (default $def)"
    }
    private fun chatModelDisplay(): String {
        val cur = ProviderConfig.chatModel(prefs())
        val def = ProviderConfig.defaultsFor(ProviderConfig.selectedChat(prefs())).chatModel
        return if (cur == def) "Default ($def) — tap to change" else "$cur — tap to change (default $def)"
    }
    private fun promptSttModelOverride() {
        val cur = ProviderConfig.sttModel(prefs())
        val input = EditText(this).apply { setText(cur); hint = "e.g. voxtral-mini-2602" }
        android.app.AlertDialog.Builder(this)
            .setTitle("STT model (override)")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isBlank()) ProviderConfig.saveSttModelOverride(prefs(), "")
                else ProviderConfig.saveSttModelOverride(prefs(), v)
                refresh()
            }
            .setNegativeButton("Clear (use default)") { _, _ -> ProviderConfig.saveSttModelOverride(prefs(), ""); refresh() }
            .setNeutralButton("Cancel", null)
            .show()
    }
    private fun promptChatModelOverride() {
        val cur = ProviderConfig.chatModel(prefs())
        val input = EditText(this).apply { setText(cur); hint = "e.g. mistral-small-latest" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Chat model (override)")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isBlank()) ProviderConfig.saveChatModelOverride(prefs(), "")
                else ProviderConfig.saveChatModelOverride(prefs(), v)
                refresh()
            }
            .setNegativeButton("Clear (use default)") { _, _ -> ProviderConfig.saveChatModelOverride(prefs(), ""); refresh() }
            .setNeutralButton("Cancel", null)
            .show()
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
        val hasSttKey = SecurePrefs.hasSttApiKey(this)
        val hasChatKey = SecurePrefs.hasChatApiKey(this)
        val hasModel = LocalTranscriber.availableModels(this).isNotEmpty()
        val selectedStt = ProviderConfig.selectedStt(prefs())
        val selectedChat = ProviderConfig.selectedChat(prefs())

        audioRowSub.text = if (audio) "Granted" else "Tap to grant permission"
        accRowSub.text = if (acc) "Enabled" else "Tap to enable in settings"

        // Visibility — STT and cleanup are independent so users can mix
        // (e.g. fal.ai for transcription + Together AI for cleanup).
        modelContainer.visibility = if (useLocal) View.VISIBLE else View.GONE
        languageRow.visibility = if (useLocal) View.GONE else View.VISIBLE
        languageRowSub.text = currentLanguageLabel()
        providerContainer.visibility = if (!useLocal) View.VISIBLE else View.GONE
        customProviderContainer.visibility = if (!useLocal && selectedStt == Provider.CUSTOM) View.VISIBLE else View.GONE
        chatProviderContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        chatCustomContainer.visibility = if (usePostProcessing && selectedChat == Provider.CUSTOM) View.VISIBLE else View.GONE
        sttKeyRow.visibility = if (!useLocal) View.VISIBLE else View.GONE
        chatKeyRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptContainer.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        promptRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        reasoningRow.visibility = if (usePostProcessing) View.VISIBLE else View.GONE
        reasoningRowSub.text = currentReasoningLabel()

        // Provider summary + radio states
        providerSummarySub.text = ProviderConfig.sttSummary(prefs())
        chatSummarySub.text = ProviderConfig.chatSummary(prefs())
        refreshProviderRows()

        customSttUrlSub.text = providerCustomSttUrlSubtitle()
        customSttModelSub.text = providerCustomSttModelSubtitle()
        customChatUrlSub.text = providerCustomChatUrlSubtitle()
        customChatModelSub.text = providerCustomChatModelSubtitle()
        // keep override rows in sync when provider changes
        (findViewById<View>(android.R.id.content)?.findViewWithTag<LinearLayout>("sttModelOverrideRow")?.findViewWithTag<TextView>("subtitle"))?.let { it.text = sttModelDisplay() }
        (findViewById<View>(android.R.id.content)?.findViewWithTag<LinearLayout>("chatModelOverrideRow")?.findViewWithTag<TextView>("subtitle"))?.let { it.text = chatModelDisplay() }

        // API key rows — per side
        sttKeyRowTitle.text = when (selectedStt) {
            Provider.GROQ -> "Groq STT key"
            Provider.OPENROUTER -> "OpenRouter STT key"
            Provider.TOGETHER -> "Together AI STT key"
            Provider.FAL -> "fal.ai key"
            Provider.CUSTOM -> "STT API key"
            else -> "OpenAI STT key"
        }
        val sttKey = SecurePrefs.getSttApiKey(this)
        sttKeyRowSub.text = if (sttKey.isBlank()) "Tap to set" else if (sttKey.length > 7) "${sttKey.take(3)}...${sttKey.takeLast(4)}" else "***"

        chatKeyRowTitle.text = when (selectedChat) {
            Provider.GROQ -> "Groq cleanup key"
            Provider.OPENROUTER -> "OpenRouter cleanup key"
            Provider.TOGETHER -> "Together AI cleanup key"
            Provider.CUSTOM -> "Cleanup API key"
            else -> "OpenAI cleanup key"
        }
        val chatKey = SecurePrefs.getChatApiKey(this)
        chatKeyRowSub.text = if (chatKey.isBlank()) "Tap to set" else if (chatKey.length > 7) "${chatKey.take(3)}...${chatKey.takeLast(4)}" else "***"

        // Legacy single key row — title reflects legacy provider (kept hidden)
        val selectedProvider = ProviderConfig.selected(prefs())
        keyRowTitle.text = when (selectedProvider) {
            Provider.GROQ -> "Groq API key"
            Provider.OPENROUTER -> "OpenRouter API key"
            Provider.TOGETHER -> "Together AI API key"
            Provider.FAL -> "fal.ai key"
            Provider.CUSTOM -> "API key"
            else -> "OpenAI API key"
        }
        val apiKey = SecurePrefs.getApiKey(this)
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
        val cloudReady = !useLocal && hasSttKey
        val postReady = !usePostProcessing || hasChatKey
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

    // Single-key shim kept hidden; split keys are the real rows.
    private fun promptApiKey(isStt: Boolean = true) { // isStt: false = chat STT/Cleanup
        val provider = if (isStt) ProviderConfig.selectedStt(prefs()) else ProviderConfig.selectedChat(prefs())
        val hint = when (provider) {
            Provider.GROQ -> "gsk_..."
            Provider.OPENROUTER -> "sk-or-..."
            Provider.TOGETHER -> "tgp_..."
            Provider.FAL -> "fal key — see https://fal.ai/dashboard/keys"
            else -> "sk-..."
        }
        val outer = this
        val input = EditText(this).apply {
            this.hint = hint
            setText(if (isStt) SecurePrefs.getSttApiKey(outer) else SecurePrefs.getChatApiKey(outer))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("${provider.displayName} ${if (isStt) "STT" else "cleanup"} key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                if (isStt) SecurePrefs.putSttApiKey(outer, input.text.toString())
                else SecurePrefs.putChatApiKey(outer, input.text.toString())
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Legacy single-key dialog (called nowhere after the split, but kept for
    // backwards compat if the hidden row is ever tapped via search/toast).
    private fun promptSingleApiKey() {
        val provider = ProviderConfig.selected(prefs())
        val hint = when (provider) {
            Provider.GROQ -> "gsk_..."
            Provider.OPENROUTER -> "sk-or-..."
            Provider.TOGETHER -> "tgp_..."
            Provider.FAL -> "fal key"
            else -> "sk-..."
        }
        val outer = this
        val input = EditText(this).apply {
            this.hint = hint
            setText(SecurePrefs.getApiKey(outer))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("${provider.displayName} API key")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                SecurePrefs.putApiKey(outer, input.text.toString())
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

    // --- Reasoning effort ---

    private fun currentReasoning(): PostProcessor.Reasoning =
        PostProcessor.Reasoning.fromKey(prefs().getString("reasoning_effort", "off"))

    private fun currentReasoningLabel(): String {
        val r = currentReasoning()
        return "${r.label} — ${r.subtitle}"
    }

    private fun promptReasoning() {
        val options = PostProcessor.Reasoning.entries
        val labels = options.map { it.label }.toTypedArray()
        val current = options.indexOf(currentReasoning()).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle("Reasoning effort")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val selected = options[which]
                prefs().edit().putString("reasoning_effort", selected.key).apply()
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Transcription language ---

    private data class LangOption(val label: String, val code: String)

    private val langOptions = listOf(
        LangOption("Auto-detect", "auto"),
        LangOption("English", "en"),
        LangOption("German", "de"),
        LangOption("Spanish", "es"),
        LangOption("French", "fr"),
        LangOption("Italian", "it"),
        LangOption("Portuguese", "pt"),
        LangOption("Dutch", "nl"),
        LangOption("Polish", "pl"),
        LangOption("Russian", "ru"),
        LangOption("Turkish", "tr"),
        LangOption("Japanese", "ja"),
        LangOption("Korean", "ko"),
        LangOption("Chinese", "zh"),
    )

    private fun currentLanguageCode(): String =
        prefs().getString("stt_language", "auto") ?: "auto"

    private fun currentLanguageLabel(): String {
        val code = currentLanguageCode()
        val known = langOptions.firstOrNull { it.code == code }
        return when {
            code == "auto" -> "Auto-detect — some providers translate non-English; pin yours to be safe"
            known != null -> known.label
            else -> "Custom ($code)"
        }
    }

    private fun promptLanguage() {
        val labels = (langOptions.map { it.label } + "Custom code…").toTypedArray()
        val current = langOptions.indexOfFirst { it.code == currentLanguageCode() }
        android.app.AlertDialog.Builder(this)
            .setTitle("Transcription language")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                if (which == labels.size - 1) promptCustomLanguage()
                else {
                    prefs().edit().putString("stt_language", langOptions[which].code).apply()
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptCustomLanguage() {
        val input = EditText(this).apply {
            hint = "ISO-639-1 code, e.g. uk"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            val cur = currentLanguageCode()
            if (cur != "auto") setText(cur)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom language code")
            .setView(input.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
            .setPositiveButton("Save") { _, _ ->
                val code = input.text.toString().trim().lowercase()
                if (code.matches(Regex("^[a-z]{2,3}$"))) {
                    prefs().edit().putString("stt_language", code).apply()
                    refresh()
                } else {
                    Toast.makeText(this, "Invalid language code", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Backup & restore ---

    private fun writeBackup(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(SettingsBackup.export(prefs()))
            } ?: throw java.io.IOException("Could not open file")
            Toast.makeText(this, "Settings exported (API key not included)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readBackup(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw java.io.IOException("Could not read file")
            val result = SettingsBackup.import(prefs(), text)
            if (result.isSuccess) {
                Toast.makeText(this, "Imported ${result.getOrNull() ?: 0} settings", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(this, "Import failed: invalid backup file", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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

    private fun buildHistorySection(): LinearLayout {
        val container = vertical(0)
        container.addView(sectionHeader("Transcription history"))

        val subtitle = {
            val c = HistoryManager.count(this)
            val b = HistoryManager.bytesUsed(this)
            val kb = (b / 1024).coerceAtLeast(0)
            "$c entries · ~${kb} KB" // quick summary
        }

        val browseRow = settingsRow("Browse history", "Open the full history screen — search, copy, delete") {
            historyLauncher.launch(android.content.Intent(this, HistoryActivity::class.java))
        }
        val dictRow = settingsRow("Dictionary", dictionarySummary()) {
            dictionaryLauncher.launch(android.content.Intent(this, DictionaryActivity::class.java))
        }

        val historyLimitRow = settingsRow(
            "Retention limit",
            "${prefs().getInt(HistoryManager.KEY_HISTORY_MAX_MB, HistoryManager.DEF_MAX_MB)} MB · ${prefs().getInt(HistoryManager.KEY_HISTORY_MAX_DAYS, HistoryManager.DEF_MAX_DAYS)} days — tap to change",
        ) {
            val curMb = prefs().getInt(HistoryManager.KEY_HISTORY_MAX_MB, HistoryManager.DEF_MAX_MB)
            val curDays = prefs().getInt(HistoryManager.KEY_HISTORY_MAX_DAYS, HistoryManager.DEF_MAX_DAYS)
            val mbInput = EditText(this).apply {
                hint = "Max MB (0 = unlimited)"
                setText(if (curMb == 0) "" else curMb.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            val daysInput = EditText(this).apply {
                hint = "Max days (0 = forever)"
                setText(if (curDays == 0) "" else curDays.toString())
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
            val form = vertical(dp(24)).apply {
                addView(TextView(this@MainActivity).apply { text = "Max stored (MB)"; textSize = 12f })
                addView(mbInput)
                addView(TextView(this@MainActivity).apply { text = "Keep for (days)"; textSize = 12f })
                addView(daysInput)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("History retention")
                .setView(form.apply { setPadding(dp(24), dp(8), dp(24), dp(8)) })
                .setPositiveButton("Save") { _, _ ->
                    val mb = mbInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: curMb
                    val days = daysInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: curDays
                    prefs().edit()
                        .putInt(HistoryManager.KEY_HISTORY_MAX_MB, mb)
                        .putInt(HistoryManager.KEY_HISTORY_MAX_DAYS, days)
                        .apply()
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        val enabledRow = run {
            val sw = MaterialSwitch(this).apply {
                isClickable = false
                isChecked = HistoryManager.enabled(prefs())
            }
            val row = settingsRow("Save history", if (sw.isChecked) "On" else "Off", sw) {
                val nv = !prefs().getBoolean(HistoryManager.KEY_HISTORY_ENABLED, HistoryManager.DEF_HISTORY_ENABLED)
                prefs().edit().putBoolean(HistoryManager.KEY_HISTORY_ENABLED, nv).apply()
                sw.isChecked = nv
                refresh()
            }
            row.tag = "history_enabled_row"
            row
        }

        val clearRow = settingsRow("Clear history", "Remove all saved transcriptions") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Clear history?")
                .setMessage("${HistoryManager.count(this)} entries will be deleted.")
                .setPositiveButton("Clear") { _, _ -> HistoryManager.clear(this); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        container.addView(enabledRow)
        container.addView(browseRow)
        container.addView(dictRow)
        container.addView(historyLimitRow)
        container.addView(clearRow)
        return container
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val LP_MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val LP_WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
