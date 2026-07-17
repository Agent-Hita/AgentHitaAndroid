package com.agenthita.app.service

/**
 * Minimal node abstraction used by [InstagramConversationHelper] so the
 * tree-walking logic can be unit-tested without the Android framework.
 * In production the service wraps [android.view.accessibility.AccessibilityNodeInfo]
 * with an anonymous implementation; tests use [FakeNode].
 */
internal interface NodeInfo {
    val viewIdResourceName: String?
    val text: CharSequence?
    val className: CharSequence?
    val childCount: Int

    /** Horizontal center of the node's on-screen bounds — used for direction. */
    val boundsCenterX: Int

    fun getChild(i: Int): NodeInfo?
}

/** One extracted message: its text plus the bubble's horizontal center. */
internal data class IgMessage(val text: String, val centerX: Int)

/**
 * Extracts conversational text from Instagram's DM message list.
 *
 * Instagram renders messages via Jetpack Compose (MetaComposeView) with no stable
 * view IDs. This helper walks the message_list RecyclerView's direct children,
 * skipping XMA / shared-post containers and the profile-context header block,
 * then recursively collects text from any TextView nodes that pass the
 * caller-supplied filter. Direction is left to the caller via [IgMessage.centerX]
 * (compare against the window midpoint).
 */
internal object InstagramConversationHelper {

    // A direct child containing any of these view IDs is the profile-context
    // header (avatar, follower counts, "View profile") — never message content.
    private val CONTEXT_HEADER_MARKERS = listOf(
        "thread_context_item_0",
        "view_profile_button"
    )

    /**
     * Collects message text from direct children of [listNode].
     *
     * @param listNode   The message_list RecyclerView node.
     * @param skipViewId Full view ID of nodes to skip (e.g. the XMA/shared-post container).
     * @param filter     Returns true for text that should be included (length check,
     *                   UI-chrome rejection, and media-label rejection are caller's responsibility).
     */
    fun collectMessagesFromList(
        listNode: NodeInfo,
        skipViewId: String,
        filter: (String) -> Boolean
    ): List<IgMessage> {
        val out = mutableListOf<IgMessage>()
        for (i in 0 until listNode.childCount) {
            val child = listNode.getChild(i) ?: continue
            if (child.viewIdResourceName == skipViewId) continue
            if (isContextHeader(child)) continue
            collectTextViews(child, out, filter)
        }
        return out
    }

    private fun isContextHeader(node: NodeInfo): Boolean {
        val id = node.viewIdResourceName
        if (id != null && CONTEXT_HEADER_MARKERS.any { id.endsWith(it) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isContextHeader(child)) return true
        }
        return false
    }

    private fun collectTextViews(node: NodeInfo, out: MutableList<IgMessage>, filter: (String) -> Boolean) {
        val text = node.text?.toString()
        if (text != null && node.className?.contains("TextView") == true && filter(text)) {
            out.add(IgMessage(text, node.boundsCenterX))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextViews(child, out, filter)
        }
    }
}
