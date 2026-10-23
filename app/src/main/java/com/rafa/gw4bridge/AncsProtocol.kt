package com.rafa.gw4bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Clean-room implementation of Apple Notification Center Service (ANCS) protocol
 * derived from reverse engineering the BLE GATT interactions on Galaxy Watch 4.
 */
object AncsProtocol {
    val SERVICE_GENERIC_ATTRIBUTE: UUID = UUID.fromString("00001801-0000-1000-8000-00805f9b34fb")
    val SERVICE_ANCS: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val CHAR_NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CHAR_CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val CHAR_DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    val DESCRIPTOR_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // EventID
    const val EVENT_ADDED = 0
    const val EVENT_MODIFIED = 1
    const val EVENT_REMOVED = 2

    // EventFlags bitmask
    const val FLAG_SILENT = 1 shl 0
    const val FLAG_IMPORTANT = 1 shl 1
    const val FLAG_PRE_EXISTING = 1 shl 2
    const val FLAG_POSITIVE_ACTION = 1 shl 3
    const val FLAG_NEGATIVE_ACTION = 1 shl 4

    // CategoryID
    const val CATEGORY_OTHER = 0
    const val CATEGORY_INCOMING_CALL = 1
    const val CATEGORY_MISSED_CALL = 2
    const val CATEGORY_VOICEMAIL = 3
    const val CATEGORY_SOCIAL = 4
    const val CATEGORY_SCHEDULE = 5
    const val CATEGORY_EMAIL = 6
    const val CATEGORY_NEWS = 7
    const val CATEGORY_HEALTH_FITNESS = 8
    const val CATEGORY_BUSINESS_FINANCE = 9
    const val CATEGORY_LOCATION = 10
    const val CATEGORY_ENTERTAINMENT = 11

    // CommandID
    const val COMMAND_GET_NOTIFICATION_ATTRIBUTES: Byte = 0
    const val COMMAND_GET_APP_ATTRIBUTES: Byte = 1
    const val COMMAND_PERFORM_NOTIFICATION_ACTION: Byte = 2

    // ActionID
    const val ACTION_POSITIVE: Byte = 0
    const val ACTION_NEGATIVE: Byte = 1

    // NotificationAttributeID
    const val ATTR_APP_IDENTIFIER = 0
    const val ATTR_TITLE = 1
    const val ATTR_SUBTITLE = 2
    const val ATTR_MESSAGE = 3
    const val ATTR_MESSAGE_SIZE = 4
    const val ATTR_DATE = 5
    const val ATTR_POSITIVE_ACTION_LABEL = 6
    const val ATTR_NEGATIVE_ACTION_LABEL = 7

    data class NotificationSourceEvent(
        val eventId: Int,
        val eventFlags: Int,
        val categoryId: Int,
        val categoryCount: Int,
        val notificationUid: Int
    ) {
        val hasPositiveAction: Boolean get() = (eventFlags and FLAG_POSITIVE_ACTION) != 0
        val hasNegativeAction: Boolean get() = (eventFlags and FLAG_NEGATIVE_ACTION) != 0
        val isSilent: Boolean get() = (eventFlags and FLAG_SILENT) != 0
        val isPreExisting: Boolean get() = (eventFlags and FLAG_PRE_EXISTING) != 0
        val isIncomingCall: Boolean get() = categoryId == CATEGORY_INCOMING_CALL
    }

    data class ParsedNotification(
        val uid: Int,
        val eventId: Int,
        val categoryId: Int,
        val eventFlags: Int,
        var appId: String = "",
        var title: String = "",
        var subtitle: String = "",
        var message: String = "",
        var date: String = "",
        var positiveLabel: String = "",
        var negativeLabel: String = ""
    ) {
        fun friendlyAppName(): String {
            return when {
                appId.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
                appId.contains("instagram", ignoreCase = true) || appId.contains("burbn", ignoreCase = true) -> "Instagram"
                appId.contains("mobilephone", ignoreCase = true) || categoryId == CATEGORY_INCOMING_CALL -> "Telefone iPhone"
                appId.contains("MobileSMS", ignoreCase = true) -> "iMessage / SMS"
                appId.contains("telegram", ignoreCase = true) -> "Telegram"
                appId.contains("gmail", ignoreCase = true) || appId.contains("mobilemail", ignoreCase = true) -> "E-mail"
                appId.contains("slack", ignoreCase = true) -> "Slack"
                appId.contains("youtube", ignoreCase = true) -> "YouTube"
                appId.contains(" nubank", ignoreCase = true) || appId.contains("nu.production", ignoreCase = true) -> "Nubank"
                appId.isNotBlank() -> appId.substringAfterLast('.')
                else -> "iPhone"
            }
        }
    }

    /**
     * Parses the 8-byte payload from ANCS Notification Source characteristic.
     */
    fun parseNotificationSource(bytes: ByteArray): NotificationSourceEvent? {
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val eventId = buffer.get().toInt() and 0xFF
        val eventFlags = buffer.get().toInt() and 0xFF
        val categoryId = buffer.get().toInt() and 0xFF
        val categoryCount = buffer.get().toInt() and 0xFF
        val uid = buffer.int
        return NotificationSourceEvent(
            eventId = eventId,
            eventFlags = eventFlags,
            categoryId = categoryId,
            categoryCount = categoryCount,
            notificationUid = uid
        )
    }

    /**
     * Builds the 18-byte GetNotificationAttributes request packet for ANCS Control Point,
     * matching the exact layout extracted from defpackage.c0.c() and defpackage.f0.
     */
    fun buildGetNotificationAttributesCommand(uid: Int): ByteArray {
        val buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(COMMAND_GET_NOTIFICATION_ATTRIBUTES)
        buf.putInt(uid)
        // Attribute 0: AppIdentifier (no max length parameter)
        buf.put(ATTR_APP_IDENTIFIER.toByte())
        // Attribute 1: Title (max length 100)
        buf.put(ATTR_TITLE.toByte())
        buf.putShort(100)
        // Attribute 2: Subtitle (max length 200)
        buf.put(ATTR_SUBTITLE.toByte())
        buf.putShort(200)
        // Attribute 3: Message (max length 300)
        buf.put(ATTR_MESSAGE.toByte())
        buf.putShort(300)
        // Attribute 5: Date (no max length parameter)
        buf.put(ATTR_DATE.toByte())
        // Attribute 6: PositiveActionLabel (no max length parameter)
        buf.put(ATTR_POSITIVE_ACTION_LABEL.toByte())
        // Attribute 7: NegativeActionLabel (no max length parameter)
        buf.put(ATTR_NEGATIVE_ACTION_LABEL.toByte())
        return buf.array()
    }

    /**
     * Builds the 6-byte PerformNotificationAction command for ANCS Control Point,
     * matching defpackage.my2.W(int, s).
     */
    fun buildPerformNotificationActionCommand(uid: Int, actionId: Byte): ByteArray {
        val buf = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(COMMAND_PERFORM_NOTIFICATION_ACTION)
        buf.putInt(uid)
        buf.put(actionId)
        return buf.array()
    }

    /**
     * Stateful assembler for potentially fragmented ANCS Data Source notifications,
     * matching defpackage.b0.m().
     */
    class DataSourceAssembler {
        private var buffer = ByteArray(0)
        private val requestedAttrs = intArrayOf(
            ATTR_APP_IDENTIFIER,
            ATTR_TITLE,
            ATTR_SUBTITLE,
            ATTR_MESSAGE,
            ATTR_DATE,
            ATTR_POSITIVE_ACTION_LABEL,
            ATTR_NEGATIVE_ACTION_LABEL
        )

        fun reset() {
            buffer = ByteArray(0)
        }

        fun feedChunk(chunk: ByteArray, pendingMap: Map<Int, ParsedNotification>): ParsedNotification? {
            buffer += chunk
            if (buffer.size < 5) return null
            if (buffer[0] != COMMAND_GET_NOTIFICATION_ATTRIBUTES) {
                reset()
                return null
            }

            val uid = ByteBuffer.wrap(buffer, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val target = pendingMap[uid] ?: ParsedNotification(uid, EVENT_ADDED, CATEGORY_OTHER, 0)

            var pos = 5
            var parsedCount = 0
            while (pos < buffer.size && parsedCount < requestedAttrs.size) {
                if (pos + 3 > buffer.size) {
                    // Need more fragments for attribute header (1 byte ID + 2 bytes length)
                    return null
                }
                val attrId = buffer[pos].toInt() and 0xFF
                val len = ByteBuffer.wrap(buffer, pos + 1, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                pos += 3
                if (pos + len > buffer.size) {
                    // Need more fragments for attribute value bytes
                    return null
                }
                val strVal = if (len > 0) {
                    String(buffer, pos, len, StandardCharsets.UTF_8)
                } else {
                    ""
                }
                pos += len
                parsedCount++

                when (attrId) {
                    ATTR_APP_IDENTIFIER -> target.appId = strVal
                    ATTR_TITLE -> target.title = strVal
                    ATTR_SUBTITLE -> target.subtitle = strVal
                    ATTR_MESSAGE -> target.message = strVal
                    ATTR_DATE -> target.date = strVal
                    ATTR_POSITIVE_ACTION_LABEL -> target.positiveLabel = strVal
                    ATTR_NEGATIVE_ACTION_LABEL -> target.negativeLabel = strVal
                }
            }

            if (parsedCount >= requestedAttrs.size) {
                reset()
                return target
            }
            return null
        }
    }
}
