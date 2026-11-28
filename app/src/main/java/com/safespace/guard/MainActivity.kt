package com.safespace.guard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvGuardStatus: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnPin: Button
    private lateinit var btnAlwaysOn: Button
    private lateinit var switchStrict: Switch

    private val handler = Handler(Looper.getMainLooper())
    private var ignoreSwitch = false

    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500)
        }
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startProtection()
            } else {
                toast("VPN permission is needed to protect this device")
            }
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvGuardStatus = findViewById(R.id.tvGuardStatus)
        btnToggle = findViewById(R.id.btnToggle)
        btnPin = findViewById(R.id.btnPin)
        btnAlwaysOn = findViewById(R.id.btnAlwaysOn)
        switchStrict = findViewById(R.id.switchStrict)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Tap once for normal toggle, hold for 5 seconds for Accessibility Settings (PIN protected)
        btnToggle.setOnTouchListener(object : View.OnTouchListener {
            private val holdHandler = Handler(Looper.getMainLooper())
            private var isHeld = false
            private var holdRunnable: Runnable? = null

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isHeld = false
                        holdRunnable = Runnable {
                            isHeld = true
                            if (!Store.hasPin(this@MainActivity)) {
                                toast("Set a parent PIN first.")
                            } else {
                                askPin("Parent PIN to access Accessibility settings") {
                                    GuardUnlock.open()
                                    toast("Settings unlocked for 30 seconds")
                                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                }
                            }
                        }.also {
                            holdHandler.postDelayed(it, 5000)
                        }
                        return false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        holdRunnable?.let { holdHandler.removeCallbacks(it) }
                        holdRunnable = null
                        if (!isHeld) {
                            v.performClick()
                            if (FilterVpnService.isRunning) {
                                askPin("Parent PIN to turn protection off") { stopProtection() }
                            } else {
                                requestStart()
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        btnPin.setOnClickListener {
            if (Store.hasPin(this)) {
                askPin("Enter current PIN") { showSetPinDialog() }
            } else {
                showSetPinDialog()
            }
        }

        // Opens the phone's VPN settings so the parent can enable
        // "Always-on VPN" and "Block connections without VPN".
        btnAlwaysOn.setOnClickListener {
            if (!Store.hasPin(this)) {
                toast("Set a parent PIN first.")
            } else {
                askPin("Parent PIN to open VPN settings") {
                    GuardUnlock.open()
                    toast("Settings unlocked for 30 seconds")
                    openVpnSettings()
                }
            }
        }

        ignoreSwitch = true
        switchStrict.isChecked = Store.isStrict(this)
        ignoreSwitch = false
        switchStrict.setOnCheckedChangeListener { _, checked ->
            if (ignoreSwitch) return@setOnCheckedChangeListener
            val applyChange = {
                Store.setStrict(this, checked)
                if (FilterVpnService.isRunning) restartProtection()
            }
            if (Store.hasPin(this)) {
                askPin("Parent PIN to change strict mode", onCancel = { setSwitch(!checked) }) { applyChange() }
            } else {
                applyChange()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshLoop)
    }

    private fun openVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedService = "$packageName/${AppGuardService::class.java.canonicalName}"
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedService, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun requestStart() {
        if (!Store.hasPin(this)) {
            toast("First choose a parent PIN. It is needed to turn protection off.")
            showSetPinDialog { requestStart() }
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermission.launch(intent) else startProtection()
    }

    private fun startProtection() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_START)
        )
        handler.postDelayed({ refresh() }, 500)
    }

    private fun stopProtection() {
        Store.setProtectionEnabled(this, false)
        startService(Intent(this, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_STOP))
        handler.postDelayed({ refresh() }, 500)
    }

    private fun restartProtection() {
        startService(Intent(this, FilterVpnService::class.java).setAction(FilterVpnService.ACTION_STOP))
        handler.postDelayed({ startProtection() }, 600)
    }

    private fun refresh() {
        val running = FilterVpnService.isRunning
        val wanted = Store.isProtectionEnabled(this)
        when {
            running -> {
                tvStatus.text = "Protection is ON"
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.brand))
                btnToggle.text = "Turn protection OFF (PIN)"
            }
            wanted -> {
                tvStatus.text = "Protection interrupted"
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
                btnToggle.text = "Resume protection"
            }
            else -> {
                tvStatus.text = "Protection is OFF"
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
                btnToggle.text = "Turn protection ON"
            }
        }

        if (isAccessibilityServiceEnabled()) {
            tvGuardStatus.text = "Uninstall protection: Active"
            tvGuardStatus.setTextColor(ContextCompat.getColor(this, R.color.brand))
        } else {
            tvGuardStatus.text = "Uninstall protection: Inactive"
            tvGuardStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
        }

        btnPin.text = if (Store.hasPin(this)) "Change parent PIN" else "Set parent PIN"
    }

    private fun setSwitch(value: Boolean) {
        ignoreSwitch = true
        switchStrict.isChecked = value
        ignoreSwitch = false
    }

    private fun pinField(hint: String): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        this.hint = hint
        setPadding(56, 40, 56, 24)
    }

    private fun askPin(title: String, onCancel: (() -> Unit)? = null, onOk: () -> Unit) {
        val input = pinField("PIN")
        var accepted = false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (Store.checkPin(this, input.text.toString())) {
                    accepted = true
                    onOk()
                } else {
                    toast("Wrong PIN")
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { if (!accepted) onCancel?.invoke() }
            .show()
    }

    private fun showSetPinDialog(onDone: (() -> Unit)? = null) {
        val input = pinField("Choose a 4-8 digit PIN")
        AlertDialog.Builder(this)
            .setTitle("Parent PIN")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8) {
                    Store.setPin(this, pin)
                    toast("PIN saved")
                    refresh()
                    onDone?.invoke()
                } else {
                    toast("PIN must be 4-8 digits")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
