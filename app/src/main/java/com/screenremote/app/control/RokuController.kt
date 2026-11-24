package com.screenremote.app.control

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Roku ECP: مجرد طلبات HTTP على المنفذ 8060. لا يحتاج اقتران.
 * المرجع: https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md
 */
class RokuController(private val host: String) : RemoteController {

    override var listener: ControllerListener? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    override fun connect() {
        // عديم الحالة — نعتبره متصلاً فوراً.
        listener?.onConnected()
    }

    override fun sendKey(key: RemoteKey) {
        val code = map(key) ?: return
        Thread {
            try {
                val body = ByteArray(0).toRequestBody(null)
                val req = Request.Builder()
                    .url("http://$host:8060/keypress/$code")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { /* نتجاهل الرد */ }
            } catch (e: Exception) {
                listener?.onError(e.message ?: "تعذّر إرسال الأمر إلى Roku")
            }
        }.start()
    }

    override fun sendText(text: String) {
        Thread {
            try {
                for (ch in text) {
                    val encoded = URLEncoder.encode(ch.toString(), "UTF-8").replace("+", "%20")
                    val req = Request.Builder()
                        .url("http://$host:8060/keypress/Lit_$encoded")
                        .post(ByteArray(0).toRequestBody(null))
                        .build()
                    client.newCall(req).execute().use { }
                }
            } catch (e: Exception) {
                listener?.onError(e.message ?: "تعذّر إرسال النص إلى Roku")
            }
        }.start()
    }

    override fun disconnect() {}

    private fun map(key: RemoteKey): String? = when (key) {
        RemoteKey.POWER -> "PowerOff"
        RemoteKey.UP -> "Up"
        RemoteKey.DOWN -> "Down"
        RemoteKey.LEFT -> "Left"
        RemoteKey.RIGHT -> "Right"
        RemoteKey.OK -> "Select"
        RemoteKey.BACK -> "Back"
        RemoteKey.HOME -> "Home"
        RemoteKey.MENU -> "Info"
        RemoteKey.VOLUME_UP -> "VolumeUp"
        RemoteKey.VOLUME_DOWN -> "VolumeDown"
        RemoteKey.MUTE -> "VolumeMute"
        RemoteKey.PLAY_PAUSE -> "Play"
        RemoteKey.REWIND -> "Rev"
        RemoteKey.FORWARD -> "Fwd"
    }
}
