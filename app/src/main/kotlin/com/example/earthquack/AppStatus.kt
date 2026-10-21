package com.example.earthquack

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AppStatus — the one thing the user must know about, if anything.
 *
 * Several parts of the app can have a problem the user cannot see by
 * looking: the tailnet node can drop or need a fresh login, the desktop can
 * disagree about encryption, the SFTP server can fail to start. Each of those
 * used to live inside the screen that caused it, so the user had to go
 * looking for it.
 *
 * This is a single, app-wide channel: whatever is most important right now
 * is published here, and the screens that can show a banner show it. Sources
 * are kept apart so one source going quiet does not erase another's
 * message, and only the most severe message is shown — a stack of warnings
 * on a dashboard is as unreadable as no warnings at all.
 */
object AppStatus {

    enum class Severity {
        /** Informational: the app is doing something the user may want to know. */
        Info,

        /** Something is wrong but the app still works. */
        Warning,

        /** Something is broken and needs the user. */
        Error,
    }

    data class Message(
        val text: String,
        val severity: Severity = Severity.Warning,
        /** Optional action label; when set, the banner offers a button. */
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
    )

    /** Source name for the tailnet node's own state. */
    const val SOURCE_NODE = "node"

    private val sources = MutableStateFlow<Map<String, Message>>(emptyMap())
    private val _message = MutableStateFlow<Message?>(null)

    /** The message to show, or null when there is nothing worth saying. */
    val message: StateFlow<Message?> = _message.asStateFlow()

    /** Publishes (or clears, with a null message) a message from one source. */
    fun publish(source: String, message: Message?) {
        val current = sources.value.toMutableMap()
        if (message == null) current.remove(source) else current[source] = message
        sources.value = current
        _message.value = current.values.maxByOrNull { it.severity.ordinal }
    }

    /** Clears every message. */
    fun clearAll() {
        sources.value = emptyMap()
        _message.value = null
    }
}
