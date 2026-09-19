package top.uwu.mikubox.ui.tools

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal RFC 5389 STUN client: sends a binding request and reads the
 * XOR-MAPPED-ADDRESS back, which is what the release build's NAT test shows.
 */
object StunClient {

    data class MappedAddress(val host: String, val port: Int) {
        override fun toString() = "$host:$port"
    }

    private const val MAGIC_COOKIE = 0x2112A442

    /**
     * Queries [server] and returns the address the NAT translated to, or null
     * when the server never answers inside [timeoutMs].
     */
    fun bindingRequest(server: String, timeoutMs: Int = 4000): MappedAddress? {
        val socket = DatagramSocket()
        try {
            socket.reuseAddress = true
            socket.soTimeout = timeoutMs
            val target = InetAddress.getByName(server)
            val request = bindingRequestBytes()
            socket.send(DatagramPacket(request, request.size, target, 19302))

            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            return parseMappedAddress(buffer, packet.length)
        } finally {
            socket.close()
        }
    }

    private fun bindingRequestBytes(): ByteArray {
        val header = byteArrayOf(
            0x00, 0x01,       // binding request
            0x00, 0x00,       // message length
            0x21, 0x12, 0xA4.toByte(), 0x42, // magic cookie
        )
        val id = ByteArray(12) // transaction id
        java.security.SecureRandom().nextBytes(id)
        return header + id
    }

    private fun parseMappedAddress(response: ByteArray, length: Int): MappedAddress? {
        if (length < 20) return null
        if (response[0].toInt() != 0x01 && response[1].toInt() != 0x01) {
            // Not a binding success response; success type is 0x0101 on the wire.
        }
        if ((response[0].toInt() and 0xFF) != 0x01 || (response[1].toInt() and 0xFF) != 0x01) return null
        var offset = 20
        while (offset + 4 <= length) {
            val type = ((response[offset].toInt() and 0xFF) shl 8) or (response[offset + 1].toInt() and 0xFF)
            val size = ((response[offset + 2].toInt() and 0xFF) shl 8) or (response[offset + 3].toInt() and 0xFF)
            if (type == 0x0020 && size >= 8) {             // XOR-MAPPED-ADDRESS
                val family = response[offset + 5].toInt() and 0xFF
                val port = ((response[offset + 6].toInt() and 0xFF) xor (MAGIC_COOKIE shr 16)) and 0xFFFF
                if (family == 0x01) {                       // IPv4
                    val ip = ByteArray(4)
                    for (index in 0 until 4) {
                        ip[index] = (response[offset + 8 + index].toInt() xor
                            (MAGIC_COOKIE shr (24 - 8 * index))).toByte()
                    }
                    return MappedAddress(ip.joinToString(".") { (it.toInt() and 0xFF).toString() }, port)
                }
                return null
            }
            offset += 4 + ((size + 3) and 3.inv())         // skip attribute incl. padding
        }
        return null
    }
}
