package com.screenremote.app.control

import com.screenremote.app.util.Net
import com.screenremote.app.util.PrefStore
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS. مصافحة تسجيل على ws://host:3000 تُظهر نافذة قبول على التلفزيون،
 * وبعد القبول نستلم client-key نخزّنه. أزرار التنقّل تُرسَل عبر "سوكِت المؤشر"
 * المنفصل (getPointerInputSocket)، أما الطاقة فعبر أمر ssap على السوكِت الرئيسي.
 */
class LgController(
    private val host: String,
    private val store: PrefStore
) : RemoteController {

    override var listener: ControllerListener? = null

    private val client: OkHttpClient = Net.trustAllClient()
    private var mainSocket: WebSocket? = null
    private var pointerSocket: WebSocket? = null
    private val counter = AtomicInteger(0)
    private var muted = false
    private val keyKey get() = "lg_key_$host"

    override fun connect() {
        val request = Request.Builder().url("ws://$host:3000").build()
        mainSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                register()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener?.onError(t.message ?: "تعذّر الاتصال بشاشة LG")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener?.onDisconnected(reason)
            }
        })
    }

    private fun register() {
        val key = store.get(keyKey)
        val payload = JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            put("manifest", manifest())
            if (!key.isNullOrEmpty()) put("client-key", key)
        }
        val register = JSONObject().apply {
            put("type", "register")
            put("id", "register_0")
            put("payload", payload)
        }
        mainSocket?.send(register.toString())
        if (key.isNullOrEmpty()) listener?.onPairingPrompt()
    }

    private fun handle(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "registered" -> {
                val key = obj.optJSONObject("payload")?.optString("client-key")
                if (!key.isNullOrEmpty()) store.put(keyKey, key)
                listener?.onConnected()
                requestPointerSocket()
            }
            "response" -> {
                val path = obj.optJSONObject("payload")?.optString("socketPath")
                if (!path.isNullOrEmpty()) openPointer(path)
            }
            "error" -> {
                val err = obj.optString("error")
                if (err.isNotBlank()) listener?.onError(err)
            }
        }
    }

    private fun requestPointerSocket() {
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "ptr_${counter.incrementAndGet()}")
            put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket")
        }
        mainSocket?.send(req.toString())
    }

    private fun openPointer(path: String) {
        val request = Request.Builder().url(path).build()
        pointerSocket = client.newWebSocket(request, object : WebSocketListener() {})
    }

    private fun ssap(uri: String, payload: JSONObject? = null) {
        val req = JSONObject().apply {
            put("type", "request")
            put("id", "req_${counter.incrementAndGet()}")
            put("uri", uri)
            if (payload != null) put("payload", payload)
        }
        mainSocket?.send(req.toString())
    }

    private fun button(name: String) {
        pointerSocket?.send("type:button\nname:$name\n\n")
    }

    override fun sendKey(key: RemoteKey) {
        when (key) {
            RemoteKey.POWER -> ssap("ssap://system/turnOff")
            RemoteKey.UP -> button("UP")
            RemoteKey.DOWN -> button("DOWN")
            RemoteKey.LEFT -> button("LEFT")
            RemoteKey.RIGHT -> button("RIGHT")
            RemoteKey.OK -> button("ENTER")
            RemoteKey.BACK -> button("BACK")
            RemoteKey.HOME -> button("HOME")
            RemoteKey.MENU -> button("MENU")
            RemoteKey.VOLUME_UP -> ssap("ssap://audio/volumeUp")
            RemoteKey.VOLUME_DOWN -> ssap("ssap://audio/volumeDown")
            RemoteKey.MUTE -> {
                muted = !muted
                ssap("ssap://audio/setMute", JSONObject().put("mute", muted))
            }
            RemoteKey.PLAY_PAUSE -> ssap("ssap://media.controls/play")
            RemoteKey.REWIND -> ssap("ssap://media.controls/rewind")
            RemoteKey.FORWARD -> ssap("ssap://media.controls/fastForward")
        }
    }

    override fun disconnect() {
        runCatching { pointerSocket?.close(1000, null) }
        runCatching { mainSocket?.close(1000, null) }
        pointerSocket = null
        mainSocket = null
    }

    private fun manifest(): JSONObject {
        val permissions = JSONArray(
            listOf(
                "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO",
                "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
                "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER",
                "READ_INSTALLED_APPS", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
                "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "CONTROL_INPUT_TEXT",
                "CONTROL_MOUSE_AND_KEYBOARD", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS"
            )
        )
        return JSONObject().apply {
            put("manifestVersion", 1)
            put("appVersion", "1.1")
            put("permissions", permissions)
        }
    }
}
