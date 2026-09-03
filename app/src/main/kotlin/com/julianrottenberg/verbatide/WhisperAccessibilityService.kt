package com.julianrottenberg.verbatide

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()

        /** ISO-639-1 → display name, for the cleanup language guard. */
        private val LANG_NAMES = mapOf(
            "en" to "English", "de" to "German", "es" to "Spanish", "fr" to "French",
            "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
            "ru" to "Russian", "tr" to "Turkish", "ja" to "Japanese", "ko" to "Korean",
            "zh" to "Chinese",
        )

        fun langName(code: String): String = LANG_NAMES[code.lowercase()] ?: code
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
        set(value) {
            field = value
            // Keep the bubble visible while working; re-evaluate focus when idle.
            handler.post { updateBubbleVisibility() }
        }
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        updateBubbleVisibility()
        // Try to load local model in background
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Fast path: the event source itself may be the input (many
                // custom editors — Flutter, Compose, WebView — never surface
                // FOCUS_INPUT at the root, so check the source first).
                // NOTE: event.source must be recycled by the caller.
                val src = try {
                    event.source
                } catch (_: Exception) {
                    null
                }
                if (src != null) {
                    try {
                        if (isTextInputNode(src) &&
                            (src.isFocused || src.isAccessibilityFocused)
                        ) {
                            updateBubbleVisibility()
                            return
                        }
                    } finally {
                        try { src.recycle() } catch (_: Exception) {}
                    }
                }
                updateBubbleVisibility()
                // The hierarchy is often not populated yet when
                // WINDOW_STATE_CHANGED fires (e.g. Kimi's first chat screen):
                // re-check shortly after so the bubble appears once the
                // input node exists.
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    handler.postDelayed(visibilityCheck, 300)
                    handler.postDelayed(visibilityCheck, 1000)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Noisy event — debounce so tree walks don't thrash.
                scheduleVisibilityCheck(150)
            }
        }
    }

    private val visibilityCheck = Runnable { updateBubbleVisibility() }

    private fun scheduleVisibilityCheck(delayMs: Long) {
        handler.removeCallbacks(visibilityCheck)
        handler.postDelayed(visibilityCheck, delayMs)
    }

    /**
     * Show the bubble only when an editable text field has focus (like Wispr
     * Flow) — or while recording/transcribing, when it must stay visible no
     * matter where focus moved. Otherwise hide it so it doesn't float over
     * unrelated apps.
     */
    private fun updateBubbleVisibility() {
        val overlay = overlayView ?: return
        val editable = if (state == State.IDLE) hasFocusedEditableField() else true
        val visible = state != State.IDLE || editable
        val newVis = if (visible) View.VISIBLE else View.GONE
        if (overlay.visibility != newVis) {
            overlay.visibility = newVis
            if (!visible) feedbackView?.visibility = View.GONE
        }
    }

    private fun hasFocusedEditableField(): Boolean {
        // 1. Fast path: the input-focused node. Accept anything that can
        //    take text, not just isEditable — WebView/Compose/Flutter nodes
        //    often expose ACTION_SET_TEXT while isEditable is false.
        // NOTE: rootInActiveWindow returns a new instance each call that
        // must be recycled.
        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            try {
                try {
                    activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
                        try {
                            if (isTextInputNode(node)) return true
                        } finally {
                            try { node.recycle() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    // fall through to traversal
                }
                try {
                    activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { node ->
                        try {
                            if (node.isFocused && isTextInputNode(node)) return true
                        } finally {
                            try { node.recycle() } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {
                    // fall through to traversal
                }
                // Quick win before the expensive multi-window walk: the
                // active root itself may already contain a focused input.
                try {
                    if (hasFocusedInputInTree(activeRoot)) return true
                } catch (_: Exception) {
                }
            } finally {
                try { activeRoot.recycle() } catch (_: Exception) {}
            }
        }

        // 2. Fallback: walk the other active/focused windows. The active
        //    window was already checked above; some editors only appear in
        //    the windows list. Each window.root returns a new instance that
        //    must be recycled.
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            try {
                windows?.filter { it.isActive || it.isFocused }?.forEach { window ->
                    try {
                        window.root?.let { roots += it }
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            for (root in roots) {
                try {
                    if (hasFocusedInputInTree(root)) return true
                } catch (_: Exception) {
                }
            }
        } finally {
            roots.forEach {
                try { it.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    /**
     * Same definition of "can take text" as the injection path, plus the
     * ACTION_SET_TEXT capability flag. Keep these two in sync: if injection
     * can paste there, the bubble should have been visible there.
     */
    private fun isTextInputNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        if (node.isEditable) return true
        if (className.contains("EditText")) return true
        if (className.contains("TerminalView")) return true
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return true
        if (findCustomPasteAction(node) != null) return true
        return false
    }

    /** DFS for a focused text-capable node. Caller owns [root]. */
    private fun hasFocusedInputInTree(root: AccessibilityNodeInfo): Boolean {
        if (root.isFocused && isTextInputNode(root)) return true
        // Some toolkits move accessibility focus without input focus.
        if (root.isAccessibilityFocused && isTextInputNode(root)) return true
        for (i in 0 until root.childCount) {
            val child = try { root.getChild(i) } catch (_: Exception) { null } ?: continue
            try {
                if (hasFocusedInputInTree(child)) return true
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(visibilityCheck)
        removeOverlay()
        super.onDestroy()
    }

    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(this)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                localTranscriber = LocalTranscriber.create(this, models.first())
            }
        } else {
            localTranscriber = LocalTranscriber.create(this, modelName)
        }
        if (localTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> cancelTranscription()
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Verbatide"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        state = State.RECORDING
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) pcmStream?.write(buf, 0, n)
            }
        }
    }

    private fun cancelTranscription() {
        Log.i(TAG, "Cancel requested")
        TranscriberClient.cancel()
        FalTranscriber.cancel()
        PostProcessor.cancel()
        // Also interrupt the pending timeout handler so it doesn't fire.
        handler.removeCallbacks(cancelTimeout)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
        showFeedback("Cancelled", 1500)
    }

    private val cancelTimeout = Runnable {
        if (state == State.TRANSCRIBING) {
            Log.w(TAG, "Transcription timed out")
            cancelTranscription()
            toast("Request timed out")
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber

        if (useLocal && local != null) {
            transcribeLocal(pcm, local)
        } else {
            transcribeApi(pcm)
        }
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

                handleTranscriptionResult(text)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                handler.post {
                    toast("Local error: ${e.message}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        // Cap recording length: 16kHz mono 16-bit = 32kB/s → 5 min ≈ 9.6 MB WAV
        if (wav.size > TranscriberClient.MAX_WAV_BYTES) {
            reset("Recording too long — try a shorter dictation")
            return
        }

        val p = prefs()
        val selectedStt = ProviderConfig.selectedStt(p)
        val sttLanguage = prefs().getString("stt_language", "auto")

        val onResult: (String?, String?, String?) -> Unit = { text, language, error ->
            if (!text.isNullOrBlank()) {
                // Prefer the user-pinned language; fall back to Whisper's
                // detected language so cleanup knows what NOT to translate.
                val pinned = prefs().getString("stt_language", "auto") ?: "auto"
                val hint = if (pinned != "auto") langName(pinned) else language
                handleTranscriptionResult(text, hint, selectedStt.name.lowercase())
            } else {
                handler.post {
                    toast("Error: ${error ?: "empty transcript"}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }

        // Arm a hard timeout so the spinner can never spin forever (the
        // user reported an endless TRANSCRIBING state with fal.ai).
        handler.postDelayed(cancelTimeout, /* 75s */ 75_000L)

        when (selectedStt) {
            Provider.FAL -> {
                val apiKey = SecurePrefs.getSttApiKey(this)
                if (apiKey.isBlank()) { handler.removeCallbacks(cancelTimeout); reset("Set STT API key (fal.ai) in Verbatide"); return }
                FalTranscriber.transcribe(wav, apiKey, sttLanguage) { r ->
                    handler.removeCallbacks(cancelTimeout)
                    onResult(r.text, r.language, r.error)
                }
            }
            else -> {
                val apiKey = SecurePrefs.getSttApiKey(this)
                if (apiKey.isBlank()) { handler.removeCallbacks(cancelTimeout); reset("Set STT API key in Verbatide"); return }
                TranscriberClient.transcribe(
                    wavData = wav,
                    apiKey = apiKey,
                    sttUrl = ProviderConfig.sttUrl(p),
                    sttModel = ProviderConfig.sttModel(p),
                    language = sttLanguage,
                ) { result ->
                    handler.removeCallbacks(cancelTimeout)
                    onResult(result.text, result.language, result.error)
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?, languageHint: String? = null, provider: String = "") {
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = SecurePrefs.getChatApiKey(this)

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    HistoryManager.append(this@WhisperAccessibilityService, HistoryEntry(System.currentTimeMillis(), text, provider, languageHint))
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            val p2 = prefs()
            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            PostProcessor.process(
                text = text,
                prompt = prompt,
                apiKey = apiKey,
                chatUrl = ProviderConfig.chatUrl(p2),
                chatModel = ProviderConfig.chatModel(p2),
                reasoning = PostProcessor.Reasoning.fromKey(prefs().getString("reasoning_effort", "off")),
                languageHint = languageHint,
            ) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        injectText(result.text)
                        HistoryManager.append(this@WhisperAccessibilityService, HistoryEntry(System.currentTimeMillis(), result.text, provider, languageHint))
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                        HistoryManager.append(this@WhisperAccessibilityService, HistoryEntry(System.currentTimeMillis(), text, provider, languageHint))
                    }
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        } else {
            handler.post {
                injectText(text)
                HistoryManager.append(this@WhisperAccessibilityService, HistoryEntry(System.currentTimeMillis(), text, provider, languageHint))
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
