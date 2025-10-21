package com.example.heartsync.ble

import kotlin.math.abs

object R02Proto {
    // === UUIDs ===
    val RXTX_NOTIFY = java.util.UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    val RXTX_WRITE  = java.util.UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val MAIN_NOTIFY = java.util.UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")

    // === Commands (15B pad + 1B checksum) ===
    fun createCommand(hex: String): ByteArray {
        val arr = mutableListOf<Int>()
        var i = 0
        while (i < hex.length) { arr += hex.substring(i, i+2).toInt(16); i += 2 }
        while (arr.size < 15) arr += 0
        val sum = arr.sum() and 0xFF
        arr += sum
        return arr.map { it.toByte() }.toByteArray()
    }
    val ENABLE  = createCommand("a104")
    val DISABLE = createCommand("a102")

    // === Telink 다중 프레임 파서 (Python decode_telink_stream과 동일 컨셉) ===
    data class Frame(val ppg: Int? = null, val accX: Int? = null, val accY: Int? = null, val accZ: Int? = null, val spo2: Int? = null)

    fun decodeTelinkStream(data: ByteArray): List<Frame> {
        val out = mutableListOf<Frame>()
        var i = 0
        val n = data.size
        fun s12(hi: Int, lo4: Int): Int {
            val raw = ((hi and 0xFF) shl 4) or (lo4 and 0x0F)
            return if ((raw and 0x800) != 0) raw - 0x1000 else raw
        }
        while (i + 2 <= n) {
            if (data[i].toInt() and 0xFF != 0xA1) { i += 1; continue }
            val st = data[i+1].toInt() and 0xFF
            when {
                st == 0x02 && i + 10 <= n -> { // PPG (관측 10B)
                    val ppg = ((data[i+2].toInt() and 0xFF) shl 8) or (data[i+3].toInt() and 0xFF)
                    out += Frame(ppg = ppg)
                    i += 10
                }
                st == 0x03 && i + 8 <= n -> { // ACC (관측 8B)
                    val accY = s12(data[i+2].toInt(), data[i+3].toInt())
                    val accZ = s12(data[i+4].toInt(), data[i+5].toInt())
                    val accX = s12(data[i+6].toInt(), data[i+7].toInt())
                    out += Frame(accX = accX, accY = accY, accZ = accZ)
                    i += 8
                }
                st == 0x01 && i + 10 <= n -> { // SpO2 (관측 10B)
                    val spo2 = ((data[i+2].toInt() and 0xFF) shl 8) or (data[i+3].toInt() and 0xFF)
                    out += Frame(spo2 = spo2)
                    i += 10
                }
                else -> { i += 1 } // 잔여 부족/미확인
            }
        }
        return out
    }
}
