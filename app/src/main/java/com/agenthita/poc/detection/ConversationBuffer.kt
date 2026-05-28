package com.agenthita.poc.detection

import java.security.MessageDigest

/**
 * In-memory sliding window of recent messages per conversation.
 *
 * Keyed by a hash of (packageName + senderName) — raw identifiers are never stored.
 * The buffer lives only in memory and is cleared when the service is destroyed,
 * satisfying the no-persistent-message-content privacy requirement.
 */
class ConversationBuffer(private val maxMessages: Int = 5) {

    // key → ring buffer of recent message texts
    private val buffers = mutableMapOf<String, ArrayDeque<String>>()

    /** Add the latest message and trim to [maxMessages]. */
    fun add(packageName: String, senderName: String, message: String) {
        val key = conversationKey(packageName, senderName)
        val deque = buffers.getOrPut(key) { ArrayDeque() }
        deque.addLast(message)
        if (deque.size > maxMessages) deque.removeFirst()
    }

    /**
     * Returns the last [maxMessages] messages for this conversation,
     * NOT including the message just added (call add() first, then getContext()
     * will include it as the last entry).
     */
    fun getContext(packageName: String, senderName: String): List<String> =
        buffers[conversationKey(packageName, senderName)]?.toList() ?: emptyList()

    private fun conversationKey(packageName: String, senderName: String): String {
        val raw = "$packageName|$senderName"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
