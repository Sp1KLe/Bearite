package com.bearite.patcher

import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Обход блокировки по SNI (DPI провайдера): начало TLS-соединения (ClientHello) уходит несколькими мелкими TCP-сегментами,
 * и фильтр не успевает собрать имя сервера. Включается автоматически, если обычное соединение не удалось (см. BearNet.call).
 * От блокировки по IP-адресу это не помогает.
 */
object Bypass {
    /** Сокет, у которого первая запись (ClientHello) режется на куски с короткими паузами. */
    private class FragSocket : Socket() {
        override fun getOutputStream(): OutputStream = Frag(super.getOutputStream())
    }

    private class Frag(out: OutputStream) : FilterOutputStream(out) {
        private var first = true
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!first || len < 40 || b[off].toInt() != 0x16) { out.write(b, off, len); return }
            first = false
            var p = off; val end = off + len
            var size = 3                      // 3 байта, затем кусочки по ~48 байт
            while (p < end) {
                val n = minOf(size, end - p)
                out.write(b, p, n); out.flush()
                p += n; size = 48
                if (p < end) try { Thread.sleep(8) } catch (_: InterruptedException) {}
            }
        }
        override fun write(b: Int) { out.write(b) }
    }

    private class Factory(private val base: SSLSocketFactory) : SSLSocketFactory() {
        private fun frag(addr: InetAddress, port: Int): Socket {
            val s = FragSocket(); s.tcpNoDelay = true
            s.connect(InetSocketAddress(addr, port), 15000)
            return s
        }
        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            val addr = s.inetAddress ?: InetAddress.getByName(host)
            try { s.close() } catch (_: Throwable) {}
            return base.createSocket(frag(addr, port), host, port, true)
        }
        override fun createSocket(host: String, port: Int): Socket = base.createSocket(frag(InetAddress.getByName(host), port), host, port, true)
        override fun createSocket(host: String, port: Int, la: InetAddress, lp: Int): Socket = createSocket(host, port)
        override fun createSocket(a: InetAddress, port: Int): Socket = base.createSocket(frag(a, port), a.hostName, port, true)
        override fun createSocket(a: InetAddress, port: Int, la: InetAddress, lp: Int): Socket = createSocket(a, port)
    }

    private val factory: SSLSocketFactory by lazy { Factory(HttpsURLConnection.getDefaultSSLSocketFactory()) }

    /** Включает дробление ClientHello для этого соединения (только для https). */
    fun apply(c: java.net.HttpURLConnection) { if (c is HttpsURLConnection) c.sslSocketFactory = factory }
}
