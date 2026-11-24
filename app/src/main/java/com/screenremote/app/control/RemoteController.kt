package com.screenremote.app.control

/** واجهة موحّدة لكل وحدات التحكم. استدعاءات الـ listener قد تأتي من خيط خلفي. */
interface RemoteController {
    var listener: ControllerListener?
    fun connect()
    fun sendKey(key: RemoteKey)
    fun disconnect()

    /** إرسال نص مكتوب إلى حقل الإدخال المحدَّد على الشاشة (بحث، كلمة سر…). */
    fun sendText(text: String) {}

    /** وحدة Android TV فقط تستخدمها لإرسال كود الاقتران الظاهر على الشاشة. */
    fun submitPairingCode(code: String) {}
}

interface ControllerListener {
    /** تم الاتصال والجهاز جاهز لاستقبال الأزرار. */
    fun onConnected()
    /** انقطع الاتصال. */
    fun onDisconnected(reason: String?)
    /** خطأ أثناء الاتصال أو الإرسال. */
    fun onError(message: String)
    /** الشاشة تعرض نافذة قبول (سامسونج / LG) — نبّه المستخدم ليقبل على التلفزيون. */
    fun onPairingPrompt()
    /** الشاشة تعرض كوداً (Android TV) — اطلب من المستخدم إدخاله. */
    fun onPairingCodeRequired()
}
