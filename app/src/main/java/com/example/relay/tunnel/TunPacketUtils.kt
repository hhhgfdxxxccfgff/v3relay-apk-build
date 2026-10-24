package com.example.relay.tunnel

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

object TunPacketUtils {

    private val ipIdCounter = AtomicInteger(100)

    fun ipToString(ip: ByteArray): String {
        return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }

    fun createTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        windowSize: Int = 65535
    ): ByteArray {
        val totalLen = 20 + 20 + payload.size
        val packet = ByteArray(totalLen)

        // 1. IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        packet[1] = 0x00.toByte() // DSCP / ECN
        packet[2] = ((totalLen ushr 8) and 0xFF).toByte()
        packet[3] = (totalLen and 0xFF).toByte()

        val id = ipIdCounter.incrementAndGet() and 0xFFFF
        packet[4] = ((id ushr 8) and 0xFF).toByte()
        packet[5] = (id and 0xFF).toByte()

        packet[6] = 0x40.toByte() // Don't fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 6.toByte()    // Protocol: TCP (6)

        // Src & Dst IP
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)

        // IP Checksum
        computeIpChecksum(packet, 0, 20)

        // 2. TCP Header (20 bytes)
        val tcpOffset = 20
        packet[tcpOffset] = ((srcPort ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((dstPort ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        // Seq (32-bit)
        packet[tcpOffset + 4] = ((seq ushr 24) and 0xFF).toByte()
        packet[tcpOffset + 5] = ((seq ushr 16) and 0xFF).toByte()
        packet[tcpOffset + 6] = ((seq ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 7] = (seq and 0xFF).toByte()

        // Ack (32-bit)
        packet[tcpOffset + 8] = ((ack ushr 24) and 0xFF).toByte()
        packet[tcpOffset + 9] = ((ack ushr 16) and 0xFF).toByte()
        packet[tcpOffset + 10] = ((ack ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 11] = (ack and 0xFF).toByte()

        // Data Offset: 5 (20 bytes header)
        packet[tcpOffset + 12] = (5 shl 4).toByte()
        packet[tcpOffset + 13] = (flags and 0xFF).toByte()

        // Window size
        packet[tcpOffset + 14] = ((windowSize ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 15] = (windowSize and 0xFF).toByte()

        // Checksum placeholder
        packet[tcpOffset + 16] = 0x00
        packet[tcpOffset + 17] = 0x00

        // Urgent pointer
        packet[tcpOffset + 18] = 0x00
        packet[tcpOffset + 19] = 0x00

        // 3. Payload
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, 40, payload.size)
        }

        // 4. Compute TCP Checksum with Pseudo-header
        val tcpLen = 20 + payload.size
        computeTcpChecksum(srcIp, dstIp, packet, tcpOffset, tcpLen)

        return packet
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int) {
        data[offset + 10] = 0
        data[offset + 11] = 0
        var sum = 0
        var i = offset
        while (i < offset + length) {
            val w = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += w
            i += 2
        }
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        data[offset + 10] = ((checksum ushr 8) and 0xFF).toByte()
        data[offset + 11] = (checksum and 0xFF).toByte()
    }

    private fun computeTcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        tcpOffset: Int,
        tcpLen: Int
    ) {
        var sum = 0L

        // Pseudo-header:
        // Src IP (4 bytes)
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)

        // Dst IP (4 bytes)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)

        // Protocol: 6 (TCP)
        sum += 6
        // TCP Length
        sum += tcpLen

        // TCP Header and Data
        var i = tcpOffset
        val end = tcpOffset + tcpLen
        while (i < end - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }

        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toInt()
        packet[tcpOffset + 16] = ((checksum ushr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (checksum and 0xFF).toByte()
    }
}
