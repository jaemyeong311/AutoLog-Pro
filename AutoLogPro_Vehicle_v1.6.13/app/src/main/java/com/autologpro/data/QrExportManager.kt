package com.autologpro.data

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * 같은 네트워크(예: 차량 핫스팟)에 붙은 스마트폰이 QR로 기록부 HTML을 열게 한다.
 * shortcut: 평문 HTTP + 일회성 토큰이라 같은 네트워크가 아니면 열리지 않는다.
 * 실서비스는 서버 업로드(만료형 HTTPS 링크)로 교체. 매니페스트에 INTERNET 권한 필요.
 * 주행 중에는 열리지 않는다: isParked가 false면 start는 null, 이미 켠 서버도 503으로 답한다.
 */
class QrExportManager(
    private val isParked: () -> Boolean,
    private val ttlMs: Long = 5 * 60_000L,
    private val maxHits: Int = 5,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var server: ServerSocket? = null

    /** html(+ ?csv로 받는 csv)을 내려주는 서버를 켜고 QR에 담을 주소를 돌려준다. 이 기기의 사설 IP를 못 찾으면 null. */
    fun start(html: String, csv: String): String? {
        stop()
        if (!isParked()) return null
        val ip = localIp() ?: return null
        val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val ss = ServerSocket(0).apply { soTimeout = 1000 }
        server = ss
        val expires = clock() + ttlMs
        thread(isDaemon = true) { serve(ss, token, html, csv, expires) }
        return "http://$ip:${ss.localPort}/r/$token"
    }

    fun stop() {
        server?.close()
        server = null
    }

    fun qr(url: String, size: Int = 512): Bitmap {
        val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { b ->
            for (x in 0 until size) for (y in 0 until size) b.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    private fun serve(ss: ServerSocket, token: String, html: String, csv: String, expires: Long) {
        var hits = 0
        try {
            while (hits < maxHits && clock() < expires) {
                val s = try { ss.accept() } catch (_: SocketTimeoutException) { continue }
                runCatching { s.use { hits += respond(it, token, html, csv) } }
            }
        } catch (_: IOException) {
            // stop()으로 닫힌 경우
        } finally {
            ss.close()
        }
    }

    private fun respond(s: Socket, token: String, html: String, csv: String): Int {
        s.soTimeout = 3000
        val line = s.getInputStream().bufferedReader().readLine().orEmpty()
        val parked = isParked()
        val isCsv = line.startsWith("GET /r/$token?csv ")
        val ok = parked && (isCsv || line.startsWith("GET /r/$token "))
        val body = (if (!parked) "주행 중에는 열 수 없습니다" else if (!ok) "not found" else if (isCsv) csv else html).toByteArray()
        val type = if (isCsv) "text/csv; charset=utf-8\r\nContent-Disposition: attachment; filename=\"driving-log.csv\""
        else "text/html; charset=utf-8"
        s.getOutputStream().run {
            write(("HTTP/1.1 ${if (ok) "200 OK" else if (!parked) "503 Service Unavailable" else "404 Not Found"}\r\nContent-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            write(body)
            flush()
        }
        return if (ok) 1 else 0
    }

    private fun localIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
}
