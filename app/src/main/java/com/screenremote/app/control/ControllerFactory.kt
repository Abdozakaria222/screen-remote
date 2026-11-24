package com.screenremote.app.control

import android.content.Context
import com.screenremote.app.control.androidtv.AndroidTvController
import com.screenremote.app.model.DeviceType
import com.screenremote.app.model.DiscoveredDevice
import com.screenremote.app.util.PrefStore

object ControllerFactory {

    fun create(ctx: Context, device: DiscoveredDevice): RemoteController? {
        val store = PrefStore(ctx)
        return when (device.type) {
            DeviceType.ROKU -> RokuController(device.ip)
            DeviceType.SAMSUNG -> SamsungController(device.ip, store)
            DeviceType.LG -> LgController(device.ip, store)
            DeviceType.ANDROID_TV -> AndroidTvController(device.ip, ctx.applicationContext, store)
            else -> null
        }
    }
}
