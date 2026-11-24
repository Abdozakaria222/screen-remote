package com.screenremote.app.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.screenremote.app.model.DeviceType
import com.screenremote.app.model.DiscoveredDevice
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * اكتشاف الشاشات على نفس الشبكة بطريقتين مكمّلتين:
 *  1) SSDP / UPnP  -> يعطي أسماء الأجهزة ويميّز Roku / Samsung / LG من وصف الجهاز.
 *  2) مسح المنافذ على الشبكة الفرعية -> يلتقط أي جهاز حتى لو لم يعلن عن نفسه،
 *     ويصنّفه حسب المنفذ المفتوح (8060 Roku, 8001/8002 Samsung, 3000 LG, 6466/6467 Android TV).
 */
class DeviceDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val found = ConcurrentHashMap<String, DiscoveredDevice>()

    @Volatile private var scanning = false
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(onUpdate: (List<DiscoveredDevice>) -> Unit) {
        if (scanning) return
        scanning = true
        found.clear()

        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("screen_remote_ssdp").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }

        Thread { runCatching { ssdpScan(onUpdate) } }.apply { isDaemon = true }.start()
        Thread { runCatching { portScan(wifi, onUpdate) } }.apply { isDaemon = true }.start()
    }

    fun stop() {
        scanning = false
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ---------------------------------------------------------------- SSDP ----

    private fun ssdpScan(onUpdate: (List<DiscoveredDevice>) -> Unit) {
        val socket = MulticastSocket()
        socket.soTimeout = 1000
        val group = InetAddress.getByName("239.255.255.250")
        val search = (
            "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: ssdp:all\r\n\r\n"
        ).toByteArray()

        repeat(3) {
            runCatching { socket.send(DatagramPacket(search, search.size, group, 1900)) }
            Thread.sleep(120)
        }

        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + 5000
        while (scanning && System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val response = String(packet.data, 0, packet.length)
                val ip = packet.address.hostAddress ?: continue
                handleSsdp(ip, response, onUpdate)
            } catch (_: SocketTimeoutException) {
                // نكمل الانتظار حتى نهاية المهلة
            } catch (_: Exception) {
                break
            }
        }
        runCatching { socket.close() }
    }

    private fun handleSsdp(ip: String, response: String, onUpdate: (List<DiscoveredDevice>) -> Unit) {
        val server = header(response, "SERVER")
        val st = header(response, "ST")
        val usn = header(response, "USN")
        val location = header(response, "LOCATION")

        var type = DeviceType.UNKNOWN
        var name = ip

        val hint = (server + " " + st + " " + usn).lowercase()
        if (hint.contains("roku")) {
            type = DeviceType.ROKU
            name = "Roku ($ip)"
        }

        if (location != null) {
            val xml = httpGet(location)
            if (xml != null) {
                val manufacturer = tag(xml, "manufacturer").lowercase()
                val friendly = tag(xml, "friendlyName")
                if (friendly.isNotBlank()) name = friendly
                when {
                    manufacturer.contains("samsung") -> type = DeviceType.SAMSUNG
                    manufacturer.contains("lg ") || manufacturer.contains("lg electronics") -> type = DeviceType.LG
                    hint.contains("roku") -> type = DeviceType.ROKU
                    manufacturer.isNotBlank() && type == DeviceType.UNKNOWN -> type = DeviceType.DLNA
                }
            }
        }

        report(DiscoveredDevice(name, ip, defaultPort(type), type), onUpdate)
    }

    // ----------------------------------------------------------- Port scan ----

    private fun portScan(wifi: WifiManager, onUpdate: (List<DiscoveredDevice>) -> Unit) {
        @Suppress("DEPRECATION")
        val ipInt = wifi.connectionInfo?.ipAddress ?: 0
        if (ipInt == 0) return

        val prefix = String.format(
            "%d.%d.%d.",
            ipInt and 0xff,
            (ipInt shr 8) and 0xff,
            (ipInt shr 16) and 0xff
        )

        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) {
            val host = prefix + i
            pool.execute { if (scanning) probe(host, onUpdate) }
        }
        pool.shutdown()
        runCatching { pool.awaitTermination(25, TimeUnit.SECONDS) }
    }

    private fun probe(ip: String, onUpdate: (List<DiscoveredDevice>) -> Unit) {
        val type = when {
            isOpen(ip, 8060) -> DeviceType.ROKU
            isOpen(ip, 8002) || isOpen(ip, 8001) -> DeviceType.SAMSUNG
            isOpen(ip, 3000) -> DeviceType.LG
            isOpen(ip, 6466) || isOpen(ip, 6467) -> DeviceType.ANDROID_TV
            else -> return
        }
        val existing = found[ip]
        val name = existing?.name?.takeIf { it != ip } ?: "${type.label} ($ip)"
        report(DiscoveredDevice(name, ip, defaultPort(type), type), onUpdate)
    }

    private fun isOpen(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 300); true }
    } catch (_: Exception) {
        false
    }

    // -------------------------------------------------------------- Helpers ---

    /** يدمج النتائج: لا ننزل بتصنيف دقيق إلى UNKNOWN، ونحتفظ بأفضل اسم متاح. */
    private fun report(device: DiscoveredDevice, onUpdate: (List<DiscoveredDevice>) -> Unit) {
        val prev = found[device.ip]
        val merged = if (prev == null) {
            device
        } else {
            val better = if (device.type.specificity >= prev.type.specificity) device.type else prev.type
            val name = listOf(device.name, prev.name).firstOrNull { it != device.ip } ?: device.name
            DiscoveredDevice(name, device.ip, defaultPort(better), better)
        }
        found[device.ip] = merged
        val snapshot = found.values.sortedBy { it.ip }
        main.post { onUpdate(snapshot) }
    }

    private fun defaultPort(type: DeviceType): Int = when (type) {
        DeviceType.ANDROID_TV -> 6466
        DeviceType.SAMSUNG -> 8002
        DeviceType.LG -> 3000
        DeviceType.ROKU -> 8060
        else -> 0
    }

    private fun header(response: String, key: String): String? {
        for (line in response.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(key, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    private fun tag(xml: String, name: String): String {
        val regex = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun httpGet(urlStr: String): String? = try {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.requestMethod = "GET"
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }
}
