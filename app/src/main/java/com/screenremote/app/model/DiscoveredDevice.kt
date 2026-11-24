package com.screenremote.app.model

enum class DeviceType(val label: String) {
    ANDROID_TV("Android TV / Google TV"),
    SAMSUNG("Samsung (Tizen)"),
    LG("LG (webOS)"),
    ROKU("Roku"),
    DLNA("UPnP / DLNA"),
    UNKNOWN("جهاز غير معروف");

    /** كل ما زاد الرقم كل ما كان التصنيف أدق (نستخدمه عند الدمج). */
    val specificity: Int
        get() = when (this) {
            ANDROID_TV, SAMSUNG, LG, ROKU -> 3
            DLNA -> 2
            UNKNOWN -> 1
        }
}

data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val type: DeviceType
) {
    val id: String get() = ip
}
