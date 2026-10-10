package com.julianrottenberg.verbatide

/**
 * Whole-utterance and inline voice commands, handled in code rather than in the
 * cleanup prompt so they work no matter which prompt the user has stored.
 *
 * Two kinds:
 *  - Whole-utterance commands: the entire transcript is just the command, e.g.
 *    "delete that". These never reach the LLM and never land in history.
 *  - Inline commands: a phrase inside a longer sentence, e.g. ". . . done new
 *    paragraph Moving on . . .", replaced with the matching break.
 *
 * Phrase matching is deliberately conservative. Anything ambiguous ("period",
 * "comma") is left alone, because dictating the word would otherwise be
 * impossible. Users who dictate those words literally can turn commands off in
 * Settings.
 */
object VoiceCommands {
    const val KEY_ENABLED = "voice_commands_enabled"
    const val DEF_ENABLED = true

    sealed interface Command {
        /** Remove the text Verbatide inserted last, in the same app. */
        data object DeleteLast : Command

        /** Whole utterance was a break command; carries the text to insert. */
        data class Break(
            val text: String,
        ) : Command
    }

    private val DELETE_PHRASES =
        setOf(
            "delete that",
            "delete this",
            "delete it",
            "scratch that",
            "scratch this",
            "undo that",
            "undo this",
            "remove that",
            "remove this",
            "delete last sentence",
            "delete the last sentence",
            "delete that sentence",
        )

    private val PARAGRAPH_PHRASES = setOf("new paragraph", "paragraph break")
    private val LINE_PHRASES = setOf("new line", "next line", "line break")

    /** Sentence and clause punctuation that can delimit an inline break phrase. */
    private const val PUNCT = ".,;:!?"

    /** Longest phrases first so "new paragraph" is not eaten by a shorter rule. */
    private val INLINE_RULES: List<Pair<String, String>> =
        listOf(
            "new paragraph" to "\n\n",
            "paragraph break" to "\n\n",
            "line break" to "\n",
            "new line" to "\n",
            "next line" to "\n",
        )

    /**
     * Lowercase, drop punctuation, collapse whitespace. Used only for matching a
     * whole utterance, never applied to text that is about to be injected.
     */
    fun normalize(raw: String): String =
        raw
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Returns the command when the whole utterance is one, otherwise null. */
    fun parse(raw: String): Command? {
        val n = normalize(raw)
        if (n.isEmpty()) return null
        if (n in DELETE_PHRASES) return Command.DeleteLast
        if (n in PARAGRAPH_PHRASES) return Command.Break("\n\n")
        if (n in LINE_PHRASES) return Command.Break("\n")
        return null
    }

    /**
     * Replaces inline break phrases with the real break.
     *
     * A phrase only counts as a command when punctuation (or the edge of the text)
     * sits on both sides of it, so "done. new paragraph. moving on" breaks while
     * "we'll add a new line item" and "first new line second" stay prose. Word
     * boundaries alone cannot tell those apart, and mangling dictation is worse
     * than missing a break. To get a break with no punctuation, say the phrase as
     * its own utterance, which [parse] handles.
     *
     * The leading punctuation is kept and the phrase plus its trailing punctuation
     * become the break.
     */
    fun applyInline(text: String): String {
        var out = text
        for ((phrase, replacement) in INLINE_RULES) {
            val pattern = Regex("(?i)(^|[$PUNCT])(\\s*)" + Regex.escape(phrase) + "(\\s*[$PUNCT]?)")
            out = out.replace(pattern) { m -> m.groupValues[1] + replacement }
        }
        // Drop the spaces the removed phrase left behind, then tidy stray
        // leading/trailing whitespace around the new breaks.
        out = out.replace(Regex(" *\\n *"), "\n").replace(Regex("\\n{3,}"), "\n\n")
        return out
    }

    /** Applies inline rules only when commands are enabled. */
    fun inlineIfEnabled(
        enabled: Boolean,
        text: String,
    ): String = if (enabled) applyInline(text) else text
}
