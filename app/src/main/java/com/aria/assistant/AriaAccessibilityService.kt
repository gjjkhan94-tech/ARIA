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
            // Give WhatsApp's UI a moment to render before we search for the Send button.
            Handler(Looper.getMainLooper()).postDelayed({ svc.tryClickSend() }, 1200)
        }
    }

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
        // Reserved for future features (reading notifications, screen state, etc.)
    }

    override fun onInterrupt() {}

    /** Looks for WhatsApp's Send button by common resource ids / descriptions and taps it. */
    private fun tryClickSend() {
        val root = rootInActiveWindow ?: return
        val candidateIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp:id/fab"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                clickNode(nodes[0])
                return
            }
        }
        // Fallback: search by content description containing "Send"
        val node = findNodeByDescription(root, "send")
        node?.let { clickNode(it) }
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
