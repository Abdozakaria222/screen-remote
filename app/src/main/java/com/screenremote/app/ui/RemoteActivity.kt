package com.screenremote.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.screenremote.app.R
import com.screenremote.app.control.ControllerFactory
import com.screenremote.app.control.ControllerListener
import com.screenremote.app.control.RemoteController
import com.screenremote.app.control.RemoteKey
import com.screenremote.app.model.DeviceType
import com.screenremote.app.model.DiscoveredDevice

class RemoteActivity : AppCompatActivity(), ControllerListener {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_IP = "ip"
        const val EXTRA_PORT = "port"
        const val EXTRA_TYPE = "type"
    }

    private var controller: RemoteController? = null
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val buttons = mutableListOf<View>()
    private var codeDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote)

        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val ip = intent.getStringExtra(EXTRA_IP) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        val typeName = intent.getStringExtra(EXTRA_TYPE) ?: DeviceType.UNKNOWN.name
        val type = runCatching { DeviceType.valueOf(typeName) }.getOrDefault(DeviceType.UNKNOWN)

        findViewById<TextView>(R.id.deviceTitle).text = name
        status = findViewById(R.id.status)

        bindButtons()

        val device = DiscoveredDevice(name, ip, port, type)
        controller = ControllerFactory.create(this, device)
        if (controller == null) {
            status.text = "هذا النوع من الأجهزة غير مدعوم"
            setButtonsEnabled(false)
            return
        }
        controller!!.listener = this
        setButtonsEnabled(false)
        status.text = getString(R.string.connecting)
        controller!!.connect()
    }

    private fun bindButtons() {
        val map = mapOf(
            R.id.btnPower to RemoteKey.POWER,
            R.id.btnUp to RemoteKey.UP,
            R.id.btnDown to RemoteKey.DOWN,
            R.id.btnLeft to RemoteKey.LEFT,
            R.id.btnRight to RemoteKey.RIGHT,
            R.id.btnOk to RemoteKey.OK,
            R.id.btnBack to RemoteKey.BACK,
            R.id.btnHome to RemoteKey.HOME,
            R.id.btnMenu to RemoteKey.MENU,
            R.id.btnVolDown to RemoteKey.VOLUME_DOWN,
            R.id.btnVolUp to RemoteKey.VOLUME_UP,
            R.id.btnMute to RemoteKey.MUTE,
            R.id.btnRewind to RemoteKey.REWIND,
            R.id.btnPlay to RemoteKey.PLAY_PAUSE,
            R.id.btnForward to RemoteKey.FORWARD
        )
        for ((id, key) in map) {
            val view = findViewById<View>(id)
            view.setOnClickListener { controller?.sendKey(key) }
            buttons.add(view)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        for (b in buttons) b.isEnabled = enabled
    }

    private fun showCodeDialog() {
        codeDialog?.dismiss()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "مثال: 1A2B3C"
        }
        codeDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_code))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    status.text = "جارٍ التحقق من الكود…"
                    controller?.submitPairingCode(code)
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { d, _ ->
                d.dismiss()
                finish()
            }
            .show()
    }

    // ---- ControllerListener (قد تأتي من خيط خلفي، لذا ننقلها للواجهة) ----

    override fun onConnected() {
        ui.post {
            status.text = getString(R.string.connected)
            setButtonsEnabled(true)
        }
    }

    override fun onDisconnected(reason: String?) {
        ui.post {
            status.text = "انقطع الاتصال" + (reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
            setButtonsEnabled(false)
        }
    }

    override fun onError(message: String) {
        ui.post {
            status.text = "خطأ: $message"
        }
    }

    override fun onPairingPrompt() {
        ui.post {
            status.text = getString(R.string.pairing_prompt)
        }
    }

    override fun onPairingCodeRequired() {
        ui.post {
            showCodeDialog()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        codeDialog?.dismiss()
        controller?.disconnect()
    }
}
