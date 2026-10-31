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
        private val LANG_NAMES =
            mapOf(
                "en" to "English",
                "de" to "German",
                "es" to "Spanish",
                "fr" to "French",
                "it" to "Italian",
                "pt" to "Portuguese",
                "nl" to "Dutch",
                "pl" to "Polish",
                "ru" to "Russian",
                "tr" to "Turkish",
                "ja" to "Japanese",
                "ko" to "Korean",
                "zh" to "Chinese",
            )

        fun langName(code: String): String = LANG_NAMES[code.lowercase()] ?: code
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    @Volatile
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
    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var pcmStream: ByteArrayOutputStream? = null

    /**
     * Bumped by every new dictation and by cancellation. Callbacks capture the
     * value they started with and drop themselves when it no longer matches —
     * otherwise a late HTTP response from a cancelled attempt injects text into
     * whatever field the user is typing in by then.
     */
    @Volatile
    private var attemptToken = 0L

    // Audio retained for this dictation, if the user opted in. Cleared once an
    // entry records it, so a later dictation never claims the same file.
    private var pendingRecordingId: String? = null
    private var pendingRecordingDurMs: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    // Last text this service injected, so "delete that" knows what to remove.
    // Scoped to the app it went into and to a time window, because the field may
    // have been edited or the user may have moved to another app since.
    private var lastInsertedText: String = ""
    private var lastInsertedPackage: String = ""
    private var lastInsertedAt: Long = 0L
    private val deleteWindowMs = 15 * 60 * 1000L
    private val hideFeedback =
        Runnable {
            feedbackView
                ?.animate()
                ?.alpha(0f)
                ?.setDuration(180)
                ?.withEndAction {
                    feedbackView?.visibility = View.GONE
                }?.start()
        }

    // Local transcription engine (loaded lazily)
    @Volatile
    private var localTranscriber: LocalTranscriber? = null

    /** Package of the app that was in front when recording stopped — drives per-app tone selection. */
    @Volatile
    private var pendingTargetPackage: String? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        PostProcessor.migrateLegacyDefaultPrompt(this)
        showOverlay()
        updateBubbleVisibility()
        // Try to load local model in background
        // Wire the on-disk diagnostic mirror: dictation happens in other apps,
        // so the log has to survive without adb attached.
        LogStore.attach(this)
        thread { initLocalModel() }
        // The overlay service, not the settings screen, is what actually runs
        // day to day — so the daily usage probe hangs off it.
        QuotaChecker.maybeCheckDaily(this)
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
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> {
                // Fast path: the event source itself may be the input (many
                // custom editors — Flutter, Compose, WebView — never surface
                // FOCUS_INPUT at the root, so check the source first).
                // NOTE: event.source must be recycled by the caller.
                val src =
                    try {
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
                        try {
                            src.recycle()
                        } catch (_: Exception) {
                        }
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
        val activeRoot =
            try {
                rootInActiveWindow
            } catch (_: Exception) {
                null
            }
        if (activeRoot != null) {
            try {
                try {
                    activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { node ->
                        try {
                            if (isTextInputNode(node)) return true
                        } finally {
                            try {
                                node.recycle()
                            } catch (_: Exception) {
                            }
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
                            try {
                                node.recycle()
                            } catch (_: Exception) {
                            }
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
                try {
                    activeRoot.recycle()
                } catch (_: Exception) {
                }
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
                try {
                    it.recycle()
                } catch (_: Exception) {
                }
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
            val child =
                try {
                    root.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
            try {
                if (hasFocusedInputInTree(child)) return true
            } finally {
                try {
                    child.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        // Nothing else stops capture: a destroyed service would otherwise keep
        // the mic open and still inject a late response into another app.
        attemptToken++
        state = State.IDLE
        handler.removeCallbacksAndMessages(null)
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        pcmStream = null
        TranscriberClient.cancel()
        PostProcessor.cancel()
        removeOverlay()
        super.onDestroy()
    }

    @Synchronized
    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        val created =
            if (modelName.isBlank()) {
                // Auto-detect first available model
                val models = LocalTranscriber.availableModels(this)
                if (models.isNotEmpty()) {
                    Log.i(TAG, "Auto-detected model: ${models.first()}")
                    LocalTranscriber.create(this, models.first())
                } else {
                    null
                }
            } else {
                LocalTranscriber.create(this, modelName)
            }
        // Swap first, then release the old recognizer so native buffers don't
        // pile up across repeated reloadModel() calls.
        val old = localTranscriber
        localTranscriber = created
        old?.release()
        if (created != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() {
        thread { initLocalModel() }
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring =
            ProgressBar(this).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
                visibility = View.GONE
            }

        val img =
            ImageView(this).apply {
                setImageResource(R.drawable.ic_mic)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(pad, pad, pad, pad)
                background = circle(COLOR_IDLE)
            }

        val overlay =
            FrameLayout(this).apply {
                addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
                addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
            }

        val params =
            WindowManager
                .LayoutParams(
                    ringSize,
                    ringSize,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = screenW - ringSize - margin
                    y = screenH / 2 - ringSize / 2
                }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
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
                        params.x =
                            if (params.x + ringSize / 2 > screenW / 2) {
                                screenW - ringSize - margin
                            } else {
                                margin
                            }
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }

                else -> {
                    false
                }
            }
        }

        val feedback =
            TextView(this).apply {
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
                background = pill(COLOR_FEEDBACK_BG)
                alpha = 0f
                visibility = View.GONE
            }

        val feedbackParams =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT,
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

    private fun circle(color: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun pill(color: Int) =
        GradientDrawable().apply {
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
        bubbleParams: WindowManager.LayoutParams,
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(
        text: String,
        durationMs: Long = 2000,
    ) {
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
            view
                .animate()
                .alpha(1f)
                .setDuration(120)
                .start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        button?.let {
            it
                .animate()
                .alpha(0.4f)
                .setDuration(500)
                .withEndAction {
                    it
                        .animate()
                        .alpha(1f)
                        .setDuration(500)
                        .withEndAction {
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
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            toast("Grant audio permission in Verbatide")
            return
        }

        val bufSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (bufSize <= 0) {
            toast("Audio capture not available on this device")
            return
        }
        audioRecord =
            try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize,
                )
            } catch (
                _: SecurityException,
            ) {
                toast("Audio permission denied")
                return
            } catch (_: IllegalArgumentException) {
                toast("Audio init failed")
                return
            }

        pcmStream = ByteArrayOutputStream()
        try {
            audioRecord!!.startRecording()
        } catch (_: IllegalStateException) {
            // Mic held by another app or a call — fail soft, don't crash.
            audioRecord?.release()
            audioRecord = null
            pcmStream = null
            toast("Microphone is busy")
            return
        }
        state = State.RECORDING
        // New dictation → new identity for its callbacks.
        attemptToken++
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()
        // Cheap prefs guard on purpose: a long-lived service may never restart,
        // so the day boundary gets noticed here. The probe itself runs on its
        // own thread and is skipped while one is already in flight.
        QuotaChecker.maybeCheckDaily(this)

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
        // Invalidate every in-flight callback, then abort the transports.
        attemptToken++
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

    private val cancelTimeout =
        Runnable {
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

        // Capture which app is in front — drives per-app tone selection.
        // Same recycle-after-use pattern as the other rootInActiveWindow callers.
        pendingTargetPackage =
            try {
                rootInActiveWindow?.let { w ->
                    val p = w.packageName?.toString()
                    w.recycle()
                    p
                }
            } catch (_: Exception) {
                null
            }
        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) {
            reset("No audio captured")
            return
        }

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber

        if (useLocal && local != null) {
            transcribeLocal(pcm, local)
        } else {
            transcribeApi(pcm)
        }
    }

    private fun transcribeLocal(
        pcm: ByteArray,
        transcriber: LocalTranscriber,
    ) {
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
        val sttLanguage = p.getString("stt_language", "auto")

        // Optional: keep the audio so this dictation can be re-transcribed later.
        // Only when the history is on — the reference lives in the history entry,
        // and without it the file would be pruned immediately.
        pendingRecordingId = null
        pendingRecordingDurMs = 0
        if (RecordingStore.enabled(p) && HistoryManager.enabled(p)) {
            val id = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}"
            if (RecordingStore.save(this, id, wav) != null) {
                pendingRecordingId = id
                pendingRecordingDurMs = ((wav.size - 44).coerceAtLeast(0) / 32)
            }
        }
        // Chain order, minus providers the usage check already knows are dry.
        val usable =
            ProviderHealth
                .sttChain(p)
                .filter { SecurePrefs.getSttApiKey(this, it).isNotBlank() }
                // Custom without a URL is not callable — skipping it here is what
                // keeps a half-configured entry from shipping audio elsewhere.
                .filter { ProviderConfig.isReady(p, ProviderConfig.Purpose.STT, it) }
        if (usable.isEmpty()) {
            val chain = ProviderConfig.chain(p, ProviderConfig.Purpose.STT)
            reset(
                if (chain.size > 1) {
                    "No key for any transcription provider — set one in Verbatide"
                } else {
                    "Set STT API key in Verbatide"
                },
            )
            return
        }

        attemptStt(wav, usable, 0, sttLanguage)
    }

    /**
     * Tries [usable] in order, moving to the next provider when this one is out
     * of quota or its key is rejected — that is the whole backup-provider rule.
     */
    private fun attemptStt(
        wav: ByteArray,
        usable: List<Provider>,
        index: Int,
        sttLanguage: String?,
    ) {
        val provider =
            usable.getOrNull(index) ?: run {
                finishTranscriptionError("No transcription provider available")
                return
            }
        val apiKey = SecurePrefs.getSttApiKey(this, provider)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        LogStore.add(
            TAG,
            "STT ${provider.displayName} attempt ${index + 1}/${usable.size} (${ProviderConfig.sttModelFor(prefs(), provider)})",
        )
        // A timeout instance per attempt: removing a shared Runnable from a late
        // callback used to strip the *next* attempt's timer. The budget scales
        // with the audio length (TranscriberClient.budgetMs).
        val token = attemptToken
        val budget = TranscriberClient.budgetMs(wav)
        val timeout =
            Runnable {
                if (token != attemptToken) return@Runnable
                if (state == State.TRANSCRIBING) {
                    Log.w(TAG, "Transcription timed out")
                    LogStore.add(TAG, "STT ${provider.displayName} timed out after ${budget / 1000}s")
                    cancelTranscription()
                    toast("Request timed out")
                }
            }
        handler.removeCallbacks(cancelTimeout)
        handler.postDelayed(timeout, budget + 5_000L)
        val onResult: (String?, String?, String?) -> Unit = cb@{ text, language, error ->
            val ms = android.os.SystemClock.elapsedRealtime() - startedAt
            handler.removeCallbacks(timeout)
            // A response that belongs to a cancelled or superseded attempt must
            // not inject text into whatever field is focused now.
            if (token != attemptToken) return@cb
            val blank = text.isNullOrBlank()
            if (!blank && error == null) {
                ProviderHealth.clear(prefs(), provider)
                ProviderHealth.markOk(prefs(), provider)
                LogStore.add(TAG, "STT ${provider.displayName} OK in ${ms}ms, ${text.orEmpty().length} chars${language?.let { ", lang=$it" } ?: ""}")
                // Prefer the user-pinned language; fall back to the provider's
                // detected language so cleanup knows what NOT to translate.
                val pinned = prefs().getString("stt_language", "auto") ?: "auto"
                val hint = if (pinned != "auto") langName(pinned) else language
                handleTranscriptionResult(
                    rawText = text,
                    languageHint = hint,
                    provider = provider.name.lowercase(),
                    model = ProviderConfig.sttModelFor(prefs(), provider),
                    fallbackDepth = index,
                    sttMs = ms,
                )
            } else if (switchToBackup(provider, error, usable, index)) {
                LogStore.add(TAG, "STT ${provider.displayName} gave up in ${ms}ms: ${SecurePrefs.redact(error ?: "empty transcript")}")
                attemptStt(wav, usable, index + 1, sttLanguage)
            } else {
                LogStore.add(TAG, "STT ${provider.displayName} failed in ${ms}ms: ${SecurePrefs.redact(error ?: "empty transcript")}")
                // Failed dictations still get an entry when the audio was kept:
                // otherwise the file would be pruned by the next append and the
                // user could not retry the recording that failed.
                if (pendingRecordingId != null) {
                    record(
                        entry(
                            "",
                            provider.name.lowercase(),
                            ProviderConfig.sttModelFor(prefs(), provider),
                            null,
                            index,
                            ms,
                        ).copy(failure = SecurePrefs.redact(error ?: "empty transcript").take(200)),
                    )
                }
                finishTranscriptionError("Error: ${error ?: "empty transcript"}")
            }
        }

        when (provider) {
            Provider.FAL -> {
                FalTranscriber.transcribe(wav, apiKey, sttLanguage) { r ->
                    handler.removeCallbacks(cancelTimeout)
                    onResult(r.text, r.language, r.error)
                }
            }

            else -> {
                TranscriberClient.transcribe(
                    wavData = wav,
                    apiKey = apiKey,
                    sttUrl = ProviderConfig.sttUrlFor(prefs(), provider),
                    sttModel = ProviderConfig.sttModelFor(prefs(), provider),
                    language = sttLanguage,
                    vocabulary = DictionaryManager.outputTerms(this),
                ) { result ->
                    handler.removeCallbacks(cancelTimeout)
                    onResult(result.text, result.language, result.error)
                }
            }
        }
    }

    /**
     * Marks [provider] unusable and announces the switch when the failure is one
     * a backup can actually fix (quota or key). Returns true when the caller
     * should retry with the next provider instead of surfacing the error.
     */
    private fun switchToBackup(
        provider: Provider,
        error: String?,
        candidates: List<Provider>,
        index: Int,
    ): Boolean {
        val failure = ProviderHealth.classify(error)
        if (failure == ProviderHealth.Failure.OTHER) return false
        val next = candidates.getOrNull(index + 1) ?: return false
        // Only pause the provider when there *is* a fallback: on the last
        // provider a 402/401 still deserves a 24 h block (nothing else to try),
        // but a transient-looking one-off should not.
        ProviderHealth.block(prefs(), provider, error ?: failure.name.lowercase())
        Log.w(TAG, "${provider.displayName} unusable (${SecurePrefs.redact(error)}) — switching to ${next.displayName}")
        LogStore.add(TAG, "Switch: ${provider.displayName} → ${next.displayName} (${failure.name.lowercase()})")
        handler.post { toast("${provider.displayName} unavailable — trying ${next.displayName}") }
        return true
    }

    private fun finishTranscriptionError(message: String) {
        handler.removeCallbacks(cancelTimeout)
        handler.post {
            toast(message)
            state = State.IDLE
            setBusy(false)
            setAppearance(COLOR_IDLE)
        }
    }

    private fun handleTranscriptionResult(
        rawText: String?,
        languageHint: String? = null,
        provider: String = "on-device",
        model: String = "",
        fallbackDepth: Int = 0,
        sttMs: Long = 0,
    ) {
        if (rawText.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
            return
        }

        // Dictionary replacements run on every transcript (cloud or local), so they
        // hold even when the provider ignored the vocabulary hint.
        val dictText = DictionaryManager.applyReplacements(this, rawText)

        // Voice commands are handled here, not in the cleanup prompt, so they
        // behave the same whatever prompt the user has stored.
        val commandsOn = prefs().getBoolean(VoiceCommands.KEY_ENABLED, VoiceCommands.DEF_ENABLED)
        when (val cmd = if (commandsOn) VoiceCommands.parse(dictText) else null) {
            VoiceCommands.Command.DeleteLast -> {
                handler.post {
                    val ok = deleteLastInsertion()
                    toast(if (ok) "Deleted last dictation" else "Nothing to delete")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            is VoiceCommands.Command.Break -> {
                // A bare "new paragraph" / "new line" inserts that break; it is
                // not dictation, so it gets no history entry.
                handler.post {
                    injectText(cmd.text, feedback = null)
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            null -> {
                Unit
            }
        }

        val text = VoiceCommands.inlineIfEnabled(commandsOn, dictText)

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)

        if (usePostProcessing) {
            // Same rule as STT: primary cleanup provider first, then the backup.
            val usable =
                ProviderHealth
                    .chatChain(prefs())
                    .filter { SecurePrefs.getChatApiKey(this, it).isNotBlank() }
                    .filter { ProviderConfig.isReady(prefs(), ProviderConfig.Purpose.CHAT, it) }
            if (usable.isEmpty()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    HistoryManager.append(
                        this@WhisperAccessibilityService,
                        entry(text, provider, model, languageHint, fallbackDepth, sttMs).copy(failure = "post-processing key missing"),
                    )
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
            attemptCleanup(
                text = text,
                prompt = prompt,
                usable = usable,
                index = 0,
                stt = entry(text, provider, model, languageHint, fallbackDepth, sttMs),
            )
        } else {
            handler.post {
                injectText(text)
                HistoryManager.append(
                    this@WhisperAccessibilityService,
                    entry(text, provider, model, languageHint, fallbackDepth, sttMs),
                )
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
    }

    /** Cleanup counterpart of [attemptStt]: same order, same fallback rule. */
    private fun attemptCleanup(
        text: String,
        prompt: String,
        usable: List<Provider>,
        index: Int,
        stt: HistoryEntry,
    ) {
        val provider =
            usable.getOrNull(index) ?: run {
                handler.post {
                    injectText(text, feedback = "Cleanup failed — raw text used", feedbackDurationMs = 4000)
                    record(
                        stt.copy(
                            ts = System.currentTimeMillis(),
                            failure = "cleanup failed on every provider",
                        ),
                    )
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
                return
            }

        val startedAt = android.os.SystemClock.elapsedRealtime()
        val model = ProviderConfig.chatModelFor(prefs(), provider)
        val reasoning = PostProcessor.Reasoning.fromKey(prefs().getString("reasoning_effort", "off"))
        LogStore.add(TAG, "Cleanup ${provider.displayName} attempt ${index + 1}/${usable.size} ($model, ${reasoning.label})")

        PostProcessor.process(
            text = text,
            prompt = prompt,
            apiKey = SecurePrefs.getChatApiKey(this, provider),
            chatUrl = ProviderConfig.chatUrlFor(prefs(), provider),
            chatModel = model,
            reasoning = reasoning,
            languageHint = stt.lang,
            keepTerms = DictionaryManager.outputTerms(this),
            toneInstruction = ToneManager.instructionFor(this@WhisperAccessibilityService, pendingTargetPackage),
        ) { result ->
            handler.post {
                val cleaned = result.text
                if (!cleaned.isNullOrBlank()) {
                    ProviderHealth.clear(prefs(), provider)
                    ProviderHealth.markOk(prefs(), provider)
                    LogStore.add(
                        TAG,
                        "Cleanup ${provider.displayName} OK in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms, ${cleaned.length} chars",
                    )
                    injectText(cleaned)
                    record(
                        stt.copy(
                            ts = System.currentTimeMillis(),
                            text = cleaned,
                            // Keep the pre-cleanup text so "try another cleanup"
                            // has something to send without another STT round-trip.
                            rawText = stt.text,
                            cleaned = true,
                            cleanupProvider = provider.name.lowercase(),
                            cleanupModel = model,
                            reasoning =
                                if (reasoning == PostProcessor.Reasoning.DEFAULT ||
                                    reasoning == PostProcessor.Reasoning.AUTO
                                ) {
                                    ""
                                } else {
                                    reasoning.key
                                },
                            cleanupMs = android.os.SystemClock.elapsedRealtime() - startedAt,
                        ),
                    )
                } else if (switchToBackup(provider, result.error, usable, index)) {
                    attemptCleanup(text, prompt, usable, index + 1, stt)
                    return@post
                } else {
                    // Surface the actual cause (HTTP code, provider message) so
                    // "cleanup doesn't work" is debuggable from the toast alone.
                    Log.w(TAG, "Cleanup failed, falling back to raw text: ${result.error}")
                    LogStore.add(TAG, "Cleanup ${provider.displayName} failed: ${result.error}")
                    val reason = result.error?.let { if (it.length > 90) it.take(90) + "…" else it }
                    injectText(
                        text,
                        feedback = if (reason != null) "Cleanup failed: $reason" else "Cleanup failed — raw copied to clipboard",
                        feedbackDurationMs = 4000,
                    )
                    record(
                        stt.copy(
                            ts = System.currentTimeMillis(),
                            failure = SecurePrefs.redact(result.error ?: "cleanup failed").take(200),
                        ),
                    )
                }
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
        }
    }

    /**
     * A history record for one dictation. The timestamp is replaced at the end
     * of the pipeline so cleanup time shows up in the age of the entry.
     */
    private fun entry(
        text: String,
        provider: String,
        model: String,
        languageHint: String?,
        fallbackDepth: Int,
        sttMs: Long,
    ): HistoryEntry =
        HistoryEntry(
            ts = System.currentTimeMillis(),
            text = text,
            provider = provider,
            lang = languageHint,
            model = model,
            fallbackDepth = fallbackDepth,
            sttMs = sttMs,
            recording = pendingRecordingId ?: "",
            durMs = pendingRecordingDurMs,
        )

    private fun record(e: HistoryEntry) {
        HistoryManager.append(this, e)
        // The entry now owns the audio file; a second dictation must not reuse it.
        pendingRecordingId = null
        pendingRecordingDurMs = 0
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
        feedbackDurationMs: Long = 2000,
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        var injectedPackage = ""
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    injectedPackage = candidate.packageName?.toString().orEmpty()
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        if (injected) {
            lastInsertedText = text
            lastInsertedPackage = injectedPackage
            lastInsertedAt = System.currentTimeMillis()
        }
        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    /**
     * Removes the last injected text from the focused field. Returns false when
     * there is nothing safe to remove: no previous injection, a different app, a
     * stale window, or the text is gone because the user edited it.
     */
    private fun deleteLastInsertion(): Boolean {
        val needle = lastInsertedText
        if (needle.isBlank()) return false
        if (System.currentTimeMillis() - lastInsertedAt > deleteWindowMs) return false

        val candidates = findInjectionCandidates()
        try {
            for (node in candidates) {
                if (lastInsertedPackage.isNotEmpty() &&
                    node.packageName?.toString() != lastInsertedPackage
                ) {
                    continue
                }
                val current = node.text?.toString().orEmpty()
                val idx = current.lastIndexOf(needle)
                if (idx < 0) continue

                val updated = current.removeRange(idx, idx + needle.length)
                val args =
                    Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            updated,
                        )
                    }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.i(TAG, "Delete last dictation => $ok")
                if (ok) {
                    val caret = idx.coerceIn(0, updated.length)
                    val selection =
                        Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
                        }
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
                    lastInsertedText = ""
                    lastInsertedPackage = ""
                    lastInsertedAt = 0L
                    return true
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }
        return false
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
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}",
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
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

    private fun tryInjectIntoNode(
        node: AccessibilityNodeInfo,
        text: String,
    ): Boolean {
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
            val args =
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        updated,
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

    private fun logNode(
        prefix: String,
        node: AccessibilityNodeInfo,
    ) {
        val actions =
            node.actionList.joinToString { action ->
                action.label?.toString() ?: action.id.toString()
            }
        // Never log node.text — it holds whatever the user typed into other
        // apps (chats, mails, forms). Length is enough for debugging.
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} textChars=${node.text?.length ?: 0} desc=${node.contentDescription} actions=[$actions]",
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)

    private fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
