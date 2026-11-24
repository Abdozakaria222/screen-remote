package com.screenremote.app.control

import android.util.Base64
import com.screenremote.app.util.Net
import com.screenremote.app.util.PrefStore
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * سامسونج Tizen (موديل 2016+). WebSocket آمن على المنفذ 8002.
 * أول اتصال تظهر نافذة "السماح" على التلفزيون، وبعد القبول نستلم توكِن نخزّنه
 * ونرفقه في الاتصالات التالية فلا تظهر النافذة مرة أخرى.
 */
class SamsungController(
    private val host: String,
    private val store: PrefStore
) : RemoteController {

    override var listener: ControllerListener? = null

    private val client = Net.trustAllClient()
    private var webSocket: WebSocket? = null
    private val nameB64 = Base64.encodeToString("Screen Remote".toByteArray(), Base64.NO_WRAP)
    private val tokenKey get() = "samsung_token_$host"

    override fun connect() {
        val token = store.get(tokenKey)
        val url = buildString {
            append("wss://$host:8002/api/v2/channels/samsung.remote.control?name=$nameB64")
            if (!token.isNullOrEmpty()) append("&token=$token")
        }

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    if (obj.optString("event") == "ms.channel.connect") {
                        val data = obj.optJSONObject("data")
                        val newToken = data?.optString("token")
                        if (!newToken.isNullOrEmpty() && newToken != "null") {
                            store.put(tokenKey, newToken)
                        }
                        listener?.onConnected()
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener?.onError(t.message ?: "تعذّر الاتصال بشاشة سامسونج")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener?.onDisconnected(reason)
            }
        })

        if (token.isNullOrEmpty()) {
            listener?.onPairingPrompt()
        }
    }

    override fun sendKey(key: RemoteKey) {
        val code = map(key) ?: return
        val msg = JSONObject().apply {
            put("method", "ms.remote.control")
            put("params", JSONObject().apply {
                put("Cmd", "Click")
                put("DataOfCmd", code)
                put("Option", "false")
                put("TypeOfRemote", "SendRemoteKey")
            })
        }
        webSocket?.send(msg.toString())
    }

    override fun disconnect() {
        runCatching { webSocket?.close(1000, null) }
        webSocket = null
    }

    private fun map(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "KEY_POWER"
        RemoteKey.UP -> "KEY_UP"
        RemoteKey.DOWN -> "KEY_DOWN"
        RemoteKey.LEFT -> "KEY_LEFT"
        RemoteKey.RIGHT -> "KEY_RIGHT"
        RemoteKey.OK -> "KEY_ENTER"
        RemoteKey.BACK -> "KEY_RETURN"
        RemoteKey.HOME -> "KEY_HOME"
        RemoteKey.MENU -> "KEY_MENU"
        RemoteKey.VOLUME_UP -> "KEY_VOLUP"
        RemoteKey.VOLUME_DOWN -> "KEY_VOLDOWN"
        RemoteKey.MUTE -> "KEY_MUTE"
        RemoteKey.PLAY_PAUSE -> "KEY_PLAY"
        RemoteKey.REWIND -> "KEY_REWIND"
        RemoteKey.FORWARD -> "KEY_FF"
    }
}
