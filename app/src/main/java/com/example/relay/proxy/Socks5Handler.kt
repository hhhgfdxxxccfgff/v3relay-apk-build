package com.example.relay.proxy

import com.example.relay.tunnel.WSTunnel
import com.example.relay.v2.V2Tunnel
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket

object Socks5Handler {

    fun handle(
        clientSocket: Socket,
        workerUrl: String,
        secret: String,
        cleanIp: String?,
        callback: WSTunnel.TunnelCallback? = null
    ) {
        try {
            clientSocket.soTimeout = 15000
            val inStream: InputStream = clientSocket.getInputStream()
            val outStream: OutputStream = clientSocket.getOutputStream()

            val ver = inStream.read()
            if (ver != 0x05) {
                clientSocket.close()
                return
            }

            val nMethods = inStream.read()
            if (nMethods <= 0) {
                clientSocket.close()
                return
            }
            val methods = ByteArray(nMethods)
            readExact(inStream, methods)

            // NO AUTH REQUIRED
            outStream.write(byteArrayOf(0x05, 0x00))
            outStream.flush()

            val reqHeader = ByteArray(4)
            readExact(inStream, reqHeader)

            if (reqHeader[0].toInt() != 0x05 || reqHeader[1].toInt() != 0x01) {
                // Command not supported
                outStream.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                outStream.flush()
                clientSocket.close()
                return
            }

            val atyp = reqHeader[3].toInt() and 0xFF
            val host = when (atyp) {
                0x01 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    readExact(inStream, ipBytes)
                    InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                }
                0x03 -> { // Domain name
                    val len = inStream.read()
                    if (len <= 0) {
                        clientSocket.close()
                        return
                    }
                    val domainBytes = ByteArray(len)
                    readExact(inStream, domainBytes)
                    String(domainBytes, Charsets.UTF_8)
                }
                0x04 -> { // IPv6
                    val ipBytes = ByteArray(16)
                    readExact(inStream, ipBytes)
                    InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                }
                else -> {
                    clientSocket.close()
                    return
                }
            }

            val p1 = inStream.read()
            val p2 = inStream.read()
            if (p1 == -1 || p2 == -1) {
                clientSocket.close()
                return
            }
            val port = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)

            // Reply Success: VER=5, REP=0, RSV=0, ATYP=1, BND.ADDR=0.0.0.0, BND.PORT=0
            outStream.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            outStream.flush()

            // python tunnel_dispatch: v2 path first; v1 (WS) as fallback
            if (V2Tunnel.dispatch(
                    cli = clientSocket,
                    host = host,
                    port = port,
                    workerParam = workerUrl,
                    secretParam = secret,
                    cleanIpParam = cleanIp,
                    first = null
                )
            ) {
                return
            }

            WSTunnel.tunnel(
                clientSocket = clientSocket,
                targetHost = host,
                targetPort = port,
                workerUrl = workerUrl,
                secret = secret,
                cleanIp = cleanIp,
                callback = callback
            )
        } catch (_: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun readExact(inStream: InputStream, buf: ByteArray) {
        var total = 0
        while (total < buf.size) {
            val count = inStream.read(buf, total, buf.size - total)
            if (count == -1) throw java.io.EOFException("Premature end of SOCKS stream")
            total += count
        }
    }
}
