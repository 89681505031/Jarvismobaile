package com.jarvis.phone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Future PHONE MODE actions will use the currently allowed app here.
        // We intentionally do not read or transmit screen content automatically.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return clickRecursive(root, text)
    }

    fun clickAnyText(vararg texts: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (text in texts) {
            if (clickRecursive(root, text)) return true
        }
        return false
    }

    fun visibleText(allowedPackages: Set<String>): String {
        val root = rootInActiveWindow ?: return ""
        if (root.packageName?.toString() !in allowedPackages) return ""
        val out = mutableListOf<String>()
        collectText(root, out)
        return out.distinct().joinToString("\n").take(12000)
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectText(it, out) }
        }
    }

    private fun clickRecursive(node: AccessibilityNodeInfo, text: String): Boolean {
        val wanted = text.trim()
        val nodeText = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val matches = wanted.isNotBlank() && (
            nodeText.equals(wanted, ignoreCase = true) ||
                description.equals(wanted, ignoreCase = true) ||
                nodeText.contains(wanted, ignoreCase = true) ||
                description.contains(wanted, ignoreCase = true)
            )

        if (matches && clickNodeOrParent(node)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickRecursive(child, wanted)) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.isEnabled &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
            current = candidate.parent
        }
        return false
    }
}
