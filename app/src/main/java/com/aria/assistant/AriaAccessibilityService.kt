package com.aria.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * This is the piece that ADB used to provide — it lets ARIA see what's on screen
 * in OTHER apps and tap things, without root or a PC. This is what makes real
 * automation (like auto-tapping Send in WhatsApp) possible from a plain APK.
 */
class AriaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AriaAccessibility"
        private var instance: AriaAccessibilityService? = null

        /** Called by DeviceActions after opening WhatsApp with a pre-filled message. */
        fun requestAutoSend() {
            val svc = instance ?: run {
                Log.w(TAG, "Accessibility service not enabled — message stays pre-filled, user must tap Send manually.")
                return
            }
            svc.pendingAutoSend = true
            // Also try a plain retry loop as a backup in case the WINDOW_STATE_CHANGED
            // event fires before WhatsApp's Send button actually exists in the tree yet.
            svc.retryClickSend(attemptsLeft = 8)
        }
    }

    private var pendingAutoSend = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "ARIA accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (pendingAutoSend && event?.packageName == "com.whatsapp") {
            // WhatsApp's window just changed (chat opened, keyboard shown, etc.) - try now.
            Handler(Looper.getMainLooper()).postDelayed({ tryClickSend() }, 400)
        }
    }

    override fun onInterrupt() {}

    /** Retries a handful of times with a delay, since WhatsApp's UI can take a moment to fully load. */
    private fun retryClickSend(attemptsLeft: Int) {
        if (attemptsLeft <= 0 || !pendingAutoSend) return
        Handler(Looper.getMainLooper()).postDelayed({
            if (pendingAutoSend && tryClickSend()) {
                // success, stop retrying
            } else {
                retryClickSend(attemptsLeft - 1)
            }
        }, 500)
    }

    /** Looks for WhatsApp's Send button by common resource ids / descriptions and taps it. Returns true if clicked. */
    private fun tryClickSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName != "com.whatsapp") return false

        val candidateIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/fab"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                clickNode(nodes[0])
                pendingAutoSend = false
                return true
            }
        }
        // Fallback: search by content description containing "Send"
        val node = findNodeByDescription(root, "send")
        if (node != null) {
            clickNode(node)
            pendingAutoSend = false
            return true
        }
        return false
    }

    private fun findNodeByDescription(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (node.contentDescription?.toString()?.lowercase()?.contains(text) == true && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByDescription(child, text)
            if (result != null) return result
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
