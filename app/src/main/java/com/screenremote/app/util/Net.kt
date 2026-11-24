package com.screenremote.app.util

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Net {

    /** مدير ثقة يقبل الشهادات الموقّعة ذاتياً (شاشات سامسونج / LG تستخدمها). */
    class TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** عميل OkHttp يقبل أي شهادة TLS، للـ WebSocket مع الشاشات الذكية. */
    fun trustAllClient(): OkHttpClient {
        val trustManagers = arrayOf<TrustManager>(TrustAllManager())
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustManagers, SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManagers[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }
}
