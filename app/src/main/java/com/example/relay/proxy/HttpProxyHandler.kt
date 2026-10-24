package com.example.relay.proxy

import android.net.Uri
import com.example.relay.tunnel.WSTunnel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

object HttpProxyHandler {

    fun handle(
        clientSocket: Socket,
        workerUrl: String,
        secret: String,
        cleanIp: String?,
        callback: WSTunnel.TunnelCallback? = null
    ) {
        try {
            clientSocket.soTimeout = 10000
            val inStream: InputStream = clientSocket.getInputStream()
            val outStream: OutputStream = clientSocket.getOutputStream()

            val headerBuf = ByteArrayOutputStream()
            val temp = ByteArray(1024)
            var splitIndex = -1

            while (splitIndex == -1) {
                val c = inStream.read(temp)
                if (c <= 0) {
                    clientSocket.close()
                    return
                }
                headerBuf.write(temp, 0, c)
                if (headerBuf.size() > 32768) {
                    clientSocket.close()
                    return
                }

                val allBytes = headerBuf.toByteArray()
                splitIndex = findDoubleCrlf(allBytes)
            }

            val allData = headerBuf.toByteArray()
            val headBytes = allData.copyOfRange(0, splitIndex)
            val restBytes = allData.copyOfRange(splitIndex + 4, allData.size)

            val headStr = String(headBytes, Charsets.ISO_8859_1)
            val lines = headStr.split("\r\n")
            if (lines.isEmpty()) {
                clientSocket.close()
                return
            }

            val firstLineParts = lines[0].split(" ")
            if (firstLineParts.size < 2) {
                clientSocket.close()
                return
            }

            val method = firstLineParts[0].trim().uppercase()
            val target = firstLineParts[1].trim()

            if (method == "CONNECT") {
                val host: String
                val port: Int
                if (target.contains(":")) {
                    val idx = target.lastIndexOf(':')
                    var h = target.substring(0, idx).trim()
                    if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
                    host = h
                    port = target.substring(idx + 1).trim().toIntOrNull() ?: 443
                } else {
                    host = target
                    port = 443
                }

                outStream.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8))
                outStream.flush()

                WSTunnel.tunnel(
                    clientSocket = clientSocket,
                    targetHost = host,
                    targetPort = port,
                    workerUrl = workerUrl,
                    secret = secret,
                    cleanIp = cleanIp,
                    firstChunk = ByteArray(0),
                    callback = callback
                )
                return
            }

            var host: String? = null
            var port = 80
            val fwdBytes: ByteArray

            if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
                val uri = Uri.parse(target)
                host = uri.host
                val scheme = uri.scheme ?: "http"
                port = if (uri.port != -1) uri.port else if (scheme.equals("https", ignoreCase = true)) 443 else 80

                val path = (if (!uri.path.isNullOrEmpty()) uri.path else "/") +
                        (if (!uri.query.isNullOrEmpty()) "?${uri.query}" else "")

                val httpVersion = if (firstLineParts.size > 2) firstLineParts[2] else "HTTP/1.1"
                val newFirstLine = "$method $path $httpVersion"

                val newHeaders = mutableListOf<String>()
                newHeaders.add(newFirstLine)
                for (i in 1 until lines.size) {
                    newHeaders.add(lines[i])
                }

                val rewrittenHead = newHeaders.joinToString("\r\n") + "\r\n\r\n"
                val out = ByteArrayOutputStream()
                out.write(rewrittenHead.toByteArray(Charsets.ISO_8859_1))
                out.write(restBytes)
                fwdBytes = out.toByteArray()
            } else {
                for (i in 1 until lines.size) {
                    val ln = lines[i]
                    if (ln.startsWith("Host:", ignoreCase = true)) {
                        val h = ln.substring(5).trim()
                        if (h.contains(":")) {
                            val idx = h.lastIndexOf(':')
                            var hh = h.substring(0, idx).trim()
                            if (hh.startsWith("[") && hh.endsWith("]")) hh = hh.substring(1, hh.length - 1)
                            host = hh
                            port = h.substring(idx + 1).trim().toIntOrNull() ?: 80
                        } else {
                            host = h
                            port = 80
                        }
                        break
                    }
                }
                fwdBytes = allData
            }

            if (host.isNullOrBlank()) {
                clientSocket.close()
                return
            }

            WSTunnel.tunnel(
                clientSocket = clientSocket,
                targetHost = host,
                targetPort = port,
                workerUrl = workerUrl,
                secret = secret,
                cleanIp = cleanIp,
                firstChunk = fwdBytes,
                callback = callback
            )

        } catch (_: Exception) {
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun findDoubleCrlf(data: ByteArray): Int {
        for (i in 0 until data.size - 3) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }
}
