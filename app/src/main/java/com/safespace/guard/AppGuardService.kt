package com.safespace.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AppGuardService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return

        // Protection must be actively running for any blocking to occur
        if (!FilterVpnService.isRunning) return

        // 1. Check for other VPN apps (by package name)
        val isVpnApp = packageName.contains("vpn", ignoreCase = true) ||
                packageName.contains("proxy", ignoreCase = true) ||
                packageName.contains("tunnel", ignoreCase = true)

        val isSettings = packageName.contains("settings", ignoreCase = true)
        val isInstaller = packageName.contains("installer", ignoreCase = true) || packageName.contains("vending", ignoreCase = true)
        val isLauncher = packageName.contains("launcher", ignoreCase = true) || packageName.contains("home", ignoreCase = true)
        val isSystemUi = packageName.contains("systemui", ignoreCase = true) || packageName.contains("systemui", ignoreCase = true)
        val isDeviceCare = packageName.contains("lool", ignoreCase = true) ||
                packageName.contains("sm", ignoreCase = true) ||
                packageName.contains("battery", ignoreCase = true) ||
                packageName.contains("care", ignoreCase = true)

        if (!isVpnApp && !isSettings && !isInstaller && !isLauncher && !isSystemUi && !isDeviceCare) return

        val rootNode = rootInActiveWindow ?: return
        try {
            if (isRestrictedScreen(rootNode, isVpnApp, isSettings, isLauncher, isSystemUi, isDeviceCare)) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                Toast.makeText(
                    applicationContext,
                    "Action blocked: SafeSpace Guard is protected by Parent PIN",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(intent)
            }
        } finally {
            // node handling
        }
    }

    private fun isRestrictedScreen(
        node: AccessibilityNodeInfo,
        isVpnApp: Boolean,
        isSettings: Boolean,
        isLauncher: Boolean,
        isSystemUi: Boolean,
        isDeviceCare: Boolean
    ): Boolean {
        if (isVpnApp) return true

        val allTexts = mutableListOf<String>()
        collectAllText(node, allTexts)
        val combinedText = allTexts.joinToString(" ").lowercase()

        if (isSettings) {
            val hasGeneralSettingsList = combinedText.contains("network & internet") ||
                    combinedText.contains("connected devices") ||
                    combinedText.contains("sound") ||
                    combinedText.contains("storage") ||
                    combinedText.contains("privacy") ||
                    combinedText.contains("about phone") ||
                    combinedText.contains("google") ||
                    combinedText.contains("system")

            if (hasGeneralSettingsList && !combinedText.contains("installed services") && !combinedText.contains("downloaded apps")) {
                val hasTargetApp = combinedText.contains("safespace guard") || combinedText.contains("com.safespace.guard")
                if (!hasTargetApp) {
                    return false
                }
            }

            // Block VPN settings sub-screen
            if (combinedText.contains("vpn") || combinedText.contains("virtual private network")) {
                return true
            }

            // Block Accessibility settings / installed services sub-screen
            if (combinedText.contains("accessibility") ||
                combinedText.contains("installed services") ||
                combinedText.contains("downloaded apps") ||
                combinedText.contains("accessibility services")) {
                return true
            }

            // Block App Info / Uninstall / Force Stop of SafeSpace Guard
            val hasTargetApp = combinedText.contains("safespace guard") ||
                    combinedText.contains("com.safespace.guard")
            if (hasTargetApp && (
                combinedText.contains("uninstall") ||
                combinedText.contains("force stop") ||
                combinedText.contains("disable") ||
                combinedText.contains("clear data") ||
                combinedText.contains("accessibility")
            )) {
                return true
            }
            return false
        }

        val hasTargetApp = combinedText.contains("safespace guard") ||
                combinedText.contains("com.safespace.guard")
        if (!hasTargetApp) return false

        if (isLauncher) {
            // Only block on launcher if user taps Uninstall or App Info (popup menu items)
            return combinedText.contains("uninstall") ||
                    combinedText.contains("remove") ||
                    combinedText.contains("app info")
        }

        if (isSystemUi) {
            // In recents, only block if user taps "active in background" or app info / uninstall / stop
            return combinedText.contains("active in background") ||
                    combinedText.contains("app info") ||
                    combinedText.contains("uninstall") ||
                    combinedText.contains("stop") ||
                    combinedText.contains("force stop")
        }

        if (isDeviceCare) {
            return combinedText.contains("background activity") ||
                    combinedText.contains("stopped") ||
                    combinedText.contains("stop") ||
                    combinedText.contains("force stop") ||
                    combinedText.contains("battery")
        }

        return false
    }

    private fun collectAllText(node: AccessibilityNodeInfo, list: MutableList<String>) {
        node.text?.toString()?.let { list.add(it) }
        node.contentDescription?.toString()?.let { list.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectAllText(child, list)
            } finally {
                // child handling
            }
        }
    }

    override fun onInterrupt() {}
}
