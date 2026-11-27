package com.safespace.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AppGuardService : AccessibilityService() {

    private var lastBlockTime = 0L
    private var lastScanTime = 0L

    companion object {
        private const val TAG = "Guard"
        private const val COOLDOWN_MS = 1000L
        private const val CONTENT_THROTTLE_MS = 80L
        private const val APP_NAME = "safespace guard"
        private const val APP_PKG = "com.safespace.guard"

        private val LAUNCHER_MENU_ITEMS = listOf("uninstall", "app info")
        private val APP_INFO_ACTIONS = listOf(
            "force stop", "uninstall", "clear data", "clear cache",
            "clear storage", "manage storage"
        )
        private val ACCESSIBILITY_MARKERS = listOf(
            "accessibility",
            "installed apps",
            "installed services",
            "downloaded apps",
            "downloaded services",
            "use safespace guard",
            "full control of this device",
            "stop safespace guard"
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return
        if (System.currentTimeMillis() - lastBlockTime < COOLDOWN_MS) return
        if (!Store.isProtectionEnabled(this)) return

        // 1. Other VPN apps
        val isVpnApp = packageName.contains("vpn", true) ||
                packageName.contains("proxy", true) ||
                packageName.contains("tunnel", true)
        if (isVpnApp) {
            blockAction()
            return
        }

        val type = event.eventType
        val isWindowEvent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        val isClickEvent = type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED

        val isSettings = packageName.contains("settings", true)
        val isInstaller = packageName.contains("installer", true) ||
                packageName.contains("vending", true) ||
                packageName.contains("permissioncontroller", true)
        val isSystemish = packageName.contains("launcher", true) ||
                packageName.contains("home", true) ||
                packageName.contains("quickstep", true) ||
                packageName.contains("systemui", true) ||
                packageName.contains("lool", true) ||
                packageName.contains("devicecare", true) ||
                packageName.contains("samsung", true) ||
                packageName.contains(".sec.", true)

        // 2. Launcher / Recents / system UI / device care
        if (isSystemish) {
            if (isClickEvent) {
                val text = eventText(event)
                if (text.contains("active in background") ||
                    text.contains("background activity")
                ) {
                    blockAction()
                    return
                }
                if (type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
                    text.contains(APP_NAME)
                ) {
                    blockAction()
                    return
                }
            }

            if (isWindowEvent && shouldScan(type)) {
                for (root in windowRoots()) {
                    if (backgroundDialog(root) || launcherMenuForOurApp(root)) {
                        blockAction()
                        return
                    }
                }
            }
            return
        }

        // 3. Uninstall dialogs from package installer / Play Store
        if (isInstaller) {
            if (isWindowEvent && shouldScan(type)) {
                val root = rootInActiveWindow ?: return
                if (hasText(root, APP_NAME) && hasText(root, "uninstall")) {
                    blockAction()
                }
            }
            return
        }

        // 4. Settings
        if (isSettings) {
            if (!isWindowEvent || !shouldScan(type)) return
            val root = rootInActiveWindow ?: return

            if (isAppInfoPage(root) ||
                isAccessibilityPageForOurApp(root) ||
                isRestrictedSettingsScreen(root)
            ) {
                blockAction()
            }
        }
    }

    /**
     * New screen opened (STATE_CHANGED) -> always scan immediately.
     * Constant content updates -> tiny throttle so we don't scan 100x a second.
     */
    private fun shouldScan(type: Int): Boolean {
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return true
        val now = System.currentTimeMillis()
        if (now - lastScanTime < CONTENT_THROTTLE_MS) return false
        lastScanTime = now
        return true
    }

    // ---------- fast text helpers (native search, no full tree dump) ----------

    private fun hasText(root: AccessibilityNodeInfo, text: String): Boolean =
        !root.findAccessibilityNodeInfosByText(text).isNullOrEmpty()

    /** True if some node's whole text (or description) equals [text]. */
    private fun hasExactText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        return nodes.any {
            it.text?.toString()?.trim().equals(text, ignoreCase = true) ||
                    it.contentDescription?.toString()?.trim().equals(text, ignoreCase = true)
        }
    }

    // ---------- detectors ----------

    /** Samsung "Check background activity" popup that lists our app. */
    private fun backgroundDialog(root: AccessibilityNodeInfo): Boolean {
        val isDialog = hasText(root, "check background activity") ||
                hasText(root, "active while running in the background")
        return isDialog && (hasText(root, APP_NAME) || hasText(root, APP_PKG))
    }

    /** Launcher long-press menu / drag-to-uninstall bar (exact texts only). */
    private fun launcherMenuForOurApp(root: AccessibilityNodeInfo): Boolean {
        if (!hasText(root, APP_NAME)) return false
        return LAUNCHER_MENU_ITEMS.any { hasExactText(root, it) }
    }

    /** Settings > Apps > SafeSpace Guard and its Storage page. */
    private fun isAppInfoPage(root: AccessibilityNodeInfo): Boolean {
        if (!hasText(root, APP_NAME)) return false
        return APP_INFO_ACTIONS.any { hasExactText(root, it) }
    }

    /** Accessibility pages that show our service. */
    private fun isAccessibilityPageForOurApp(root: AccessibilityNodeInfo): Boolean {
        if (!hasText(root, APP_NAME) && !hasText(root, APP_PKG)) return false
        return ACCESSIBILITY_MARKERS.any { hasText(root, it) }
    }

    /** VPN settings and background-activity pages. */
    private fun isRestrictedSettingsScreen(root: AccessibilityNodeInfo): Boolean {
        if (!hasText(root, APP_NAME) && !hasText(root, APP_PKG)) return false

        val general = hasText(root, "network & internet") ||
                hasText(root, "connected devices") ||
                hasText(root, "about phone")
        if (general &&
            !hasText(root, "installed services") &&
            !hasText(root, "downloaded apps")
        ) {
            return false
        }

        return hasText(root, "vpn") ||
                hasText(root, "virtual private network") ||
                hasText(root, "active in background") ||
                hasText(root, "background activity")
    }

    // ---------- helpers ----------

    private fun eventText(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        event.contentDescription?.let { sb.append(it).append(' ') }
        val src = event.source
        if (src != null) {
            src.text?.let { sb.append(it).append(' ') }
            src.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until src.childCount) {
                val c = src.getChild(i) ?: continue
                c.text?.let { sb.append(it).append(' ') }
                c.contentDescription?.let { sb.append(it).append(' ') }
            }
            val p = src.parent
            if (p != null) {
                p.text?.let { sb.append(it).append(' ') }
                p.contentDescription?.let { sb.append(it).append(' ') }
            }
        }
        return sb.toString().lowercase()
    }

    /** Roots of all visible windows except our own (popups are separate windows). */
    private fun windowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w ->
                val r = w.root ?: return@forEach
                if (r.packageName?.toString() != applicationContext.packageName) {
                    roots.add(r)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "windows scan failed: ${e.message}")
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }
        return roots
    }

    private fun blockAction() {
        lastBlockTime = System.currentTimeMillis()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(intent)

        Toast.makeText(
            applicationContext,
            "Action blocked: SafeSpace Guard is protected by Parent PIN",
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onInterrupt() {}
}
