package com.screenremote.app.control.androidtv

import android.content.Context
import com.google.protobuf.ByteString
import com.screenremote.app.control.ControllerListener
import com.screenremote.app.control.RemoteController
import com.screenremote.app.control.RemoteKey
import com.screenremote.app.proto.pairing.PairingConfiguration
import com.screenremote.app.proto.pairing.PairingEncoding
import com.screenremote.app.proto.pairing.PairingMessage
import com.screenremote.app.proto.pairing.PairingOption
import com.screenremote.app.proto.pairing.PairingRequest
import com.screenremote.app.proto.pairing.PairingSecret
import com.screenremote.app.proto.remote.RemoteConfigure
import com.screenremote.app.proto.remote.RemoteDeviceInfo
import com.screenremote.app.proto.remote.RemoteDirection
import com.screenremote.app.proto.remote.RemoteKeyCode
import com.screenremote.app.proto.remote.RemoteKeyInject
import com.screenremote.app.proto.remote.RemoteMessage
import com.screenremote.app.proto.remote.RemotePingResponse
import com.screenremote.app.proto.remote.RemoteSetActive
import com.screenremote.app.util.PrefStore
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote v2.
 *  - الاقتران على المنفذ 6467 (TLS): نرسل طلباً، التلفزيون يعرض كوداً سداسياً،
 *    نُدخله فنحسب السر ونرسله فتُعتمد شهادتنا.
 *  - الاتصال على المنفذ 6466 (TLS، نفس الشهادة): رسائل protobuf للتهيئة والـ ping
 *    وحقن الأزرار.
 * ملاحظة: هذا البروتوكول هو الأعقد ويُنصح بتجربته على جهاز فعلي وضبط خطوة
 * الاقتران إن لزم من سجلّ الأخطاء.
 */
class AndroidTvController(
    private val host: String,
    private val ctx: Context,
    private val store: PrefStore
) : RemoteController {

    override var listener: ControllerListener? = null

    private val codeQueue = LinkedBlockingQueue<String>(1)
    private var remoteSocket: SSLSocket? = null
    private var out: OutputStream? = null
    @Volatile private var running = false
    private val pairedKey get() = "atv_paired_$host"

    override fun connect() {
        Thread { runConnect() }.apply { isDaemon = true }.start()
    }

    override fun submitPairingCode(code: String) {
        codeQueue.offer(code)
    }

    private fun runConnect() {
        try {
            val keyManagers = CertUtil.keyManagers(ctx)
            if (store.get(pairedKey) != "1") {
                doPairing(keyManagers)
                store.put(pairedKey, "1")
                Thread.sleep(1200)
            }
            try {
                openRemote(keyManagers)
            } catch (e: Exception) {
                // قد تكون الشهادة لم تعد موثوقة لدى التلفزيون — نعيد الاقتران مرة واحدة.
                store.clear(pairedKey)
                doPairing(keyManagers)
                store.put(pairedKey, "1")
                Thread.sleep(1200)
                openRemote(keyManagers)
            }
        } catch (e: Exception) {
            listener?.onError(e.message ?: "تعذّر الاتصال بجهاز Android TV")
        }
    }

    private fun doPairing(keyManagers: Array<javax.net.ssl.KeyManager>) {
        val sslContext = CertUtil.sslContext(keyManagers)
        val socket = sslContext.socketFactory.createSocket(host, 6467) as SSLSocket
        socket.soTimeout = 15000
        socket.startHandshake()
        val serverCert = socket.session.peerCertificates[0] as java.security.cert.X509Certificate
        val clientCert = CertUtil.clientCert(ctx)
        val input = socket.inputStream
        val output = socket.outputStream

        // 1) طلب الاقتران
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingRequest(
                PairingRequest.newBuilder()
                    .setServiceName("com.screenremote.app")
                    .setClientName("Screen Remote")
                    .build()
            ).build().writeDelimitedTo(output)
        output.flush()
        PairingMessage.parseDelimitedFrom(input) ?: throw java.io.IOException("لا رد من التلفزيون")

        // 2) خيارات الترميز (كود سداسي بطول 6)
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingOption(
                PairingOption.newBuilder()
                    .addInputEncodings(
                        PairingEncoding.newBuilder()
                            .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                            .setSymbolLength(6)
                            .build()
                    )
                    .setPreferredRole(PairingMessage.RoleType.ROLE_TYPE_INPUT)
                    .build()
            ).build().writeDelimitedTo(output)
        output.flush()
        PairingMessage.parseDelimitedFrom(input)

        // 3) التهيئة
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingConfiguration(
                PairingConfiguration.newBuilder()
                    .setEncoding(
                        PairingEncoding.newBuilder()
                            .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
                            .setSymbolLength(6)
                            .build()
                    )
                    .setClientRole(PairingMessage.RoleType.ROLE_TYPE_INPUT)
                    .build()
            ).build().writeDelimitedTo(output)
        output.flush()
        PairingMessage.parseDelimitedFrom(input)

        // 4) اطلب الكود من المستخدم ثم أرسل السر
        codeQueue.clear()
        listener?.onPairingCodeRequired()
        val code = codeQueue.poll(120, TimeUnit.SECONDS)
            ?: throw java.io.IOException("انتهت مهلة إدخال الكود")

        val secret = CertUtil.pairingSecret(code, clientCert, serverCert)
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .setPairingSecret(
                PairingSecret.newBuilder().setSecret(ByteString.copyFrom(secret)).build()
            ).build().writeDelimitedTo(output)
        output.flush()

        val ack = PairingMessage.parseDelimitedFrom(input)
        if (ack == null || ack.status != PairingMessage.Status.STATUS_OK) {
            throw java.io.IOException("فشل الاقتران — تأكد من الكود")
        }
        runCatching { socket.close() }
    }

    private fun openRemote(keyManagers: Array<javax.net.ssl.KeyManager>) {
        val sslContext = CertUtil.sslContext(keyManagers)
        val socket = sslContext.socketFactory.createSocket(host, 6466) as SSLSocket
        socket.startHandshake()
        remoteSocket = socket
        out = socket.outputStream
        val input = socket.inputStream
        running = true
        var active = false

        while (running) {
            val msg = RemoteMessage.parseDelimitedFrom(input) ?: break
            when {
                msg.hasRemoteConfigure() -> {
                    send(
                        RemoteMessage.newBuilder().setRemoteConfigure(
                            RemoteConfigure.newBuilder()
                                .setCode1(622)
                                .setDeviceInfo(
                                    RemoteDeviceInfo.newBuilder()
                                        .setModel("Screen Remote")
                                        .setVendor("screenremote")
                                        .setUnknown1(1)
                                        .setUnknown2("1")
                                        .setPackageName("com.screenremote.app")
                                        .setAppVersion("1.0.0")
                                        .build()
                                ).build()
                        ).build()
                    )
                }
                msg.hasRemoteSetActive() -> {
                    send(
                        RemoteMessage.newBuilder().setRemoteSetActive(
                            RemoteSetActive.newBuilder().setActive(622).build()
                        ).build()
                    )
                    if (!active) {
                        active = true
                        listener?.onConnected()
                    }
                }
                msg.hasRemotePingRequest() -> {
                    send(
                        RemoteMessage.newBuilder().setRemotePingResponse(
                            RemotePingResponse.newBuilder().setVal1(msg.remotePingRequest.val1).build()
                        ).build()
                    )
                }
            }
        }
        running = false
        listener?.onDisconnected(null)
    }

    @Synchronized
    private fun send(message: RemoteMessage) {
        val o = out ?: return
        message.writeDelimitedTo(o)
        o.flush()
    }

    override fun sendKey(key: RemoteKey) {
        val code = map(key) ?: return
        Thread {
            try {
                send(
                    RemoteMessage.newBuilder().setRemoteKeyInject(
                        RemoteKeyInject.newBuilder()
                            .setKeyCode(code)
                            .setDirection(RemoteDirection.SHORT)
                            .build()
                    ).build()
                )
            } catch (e: Exception) {
                listener?.onError(e.message ?: "تعذّر إرسال الزر")
            }
        }.start()
    }

    override fun disconnect() {
        running = false
        runCatching { remoteSocket?.close() }
        remoteSocket = null
        out = null
    }

    private fun map(key: RemoteKey): RemoteKeyCode? = when (key) {
        RemoteKey.POWER -> RemoteKeyCode.KEYCODE_POWER
        RemoteKey.UP -> RemoteKeyCode.KEYCODE_DPAD_UP
        RemoteKey.DOWN -> RemoteKeyCode.KEYCODE_DPAD_DOWN
        RemoteKey.LEFT -> RemoteKeyCode.KEYCODE_DPAD_LEFT
        RemoteKey.RIGHT -> RemoteKeyCode.KEYCODE_DPAD_RIGHT
        RemoteKey.OK -> RemoteKeyCode.KEYCODE_DPAD_CENTER
        RemoteKey.BACK -> RemoteKeyCode.KEYCODE_BACK
        RemoteKey.HOME -> RemoteKeyCode.KEYCODE_HOME
        RemoteKey.MENU -> RemoteKeyCode.KEYCODE_MENU
        RemoteKey.VOLUME_UP -> RemoteKeyCode.KEYCODE_VOLUME_UP
        RemoteKey.VOLUME_DOWN -> RemoteKeyCode.KEYCODE_VOLUME_DOWN
        RemoteKey.MUTE -> RemoteKeyCode.KEYCODE_VOLUME_MUTE
        RemoteKey.PLAY_PAUSE -> RemoteKeyCode.KEYCODE_MEDIA_PLAY_PAUSE
        RemoteKey.REWIND -> RemoteKeyCode.KEYCODE_MEDIA_REWIND
        RemoteKey.FORWARD -> RemoteKeyCode.KEYCODE_MEDIA_FAST_FORWARD
    }
}
