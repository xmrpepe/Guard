package com.rocksguard.core.upstream

import com.rocksguard.core.dns.DnsWire
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Lets the Android layer exclude resolver sockets from the VPN (VpnService.protect). */
interface SocketProtector {
    fun protect(socket: Socket): Boolean
    fun protect(socket: DatagramSocket): Boolean

    object None : SocketProtector {
        override fun protect(socket: Socket) = true
        override fun protect(socket: DatagramSocket) = true
    }
}

/** One way of reaching one resolver. */
interface DnsTransport {
    val label: String
    val encrypted: Boolean

    /** Sends a wire-format query and returns the wire-format response. */
    @Throws(IOException::class)
    fun exchange(query: ByteArray): ByteArray

    fun close() {}
}

/** Socket factory that applies [SocketProtector] to every socket it creates. */
class ProtectedSocketFactory(private val protector: SocketProtector) : SocketFactory() {
    private fun p(s: Socket): Socket = s.also { protector.protect(it) }
    override fun createSocket(): Socket = p(Socket())
    override fun createSocket(host: String, port: Int): Socket = p(Socket()).apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String, port: Int, la: InetAddress, lp: Int): Socket =
        p(Socket()).apply { bind(InetSocketAddress(la, lp)); connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress, port: Int): Socket = p(Socket()).apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress, port: Int, la: InetAddress, lp: Int): Socket =
        p(Socket()).apply { bind(InetSocketAddress(la, lp)); connect(InetSocketAddress(host, port)) }
}

/**
 * Resolves the resolver's own hostname from pinned bootstrap addresses, so connecting to
 * an encrypted resolver never leaks a plaintext lookup. Falls back to the system only
 * when no bootstrap address is known (custom resolvers).
 */
class BootstrapDns(private val pinned: Map<String, List<InetAddress>>) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        pinned[hostname.lowercase()]?.takeIf { it.isNotEmpty() } ?: Dns.SYSTEM.lookup(hostname)
}

/** RFC 8484 DNS-over-HTTPS (POST, application/dns-message). HTTP/2 connections are reused. */
class DohTransport(
    private val url: String,
    private val client: OkHttpClient,
    override val label: String = url,
) : DnsTransport {
    override val encrypted = true

    override fun exchange(query: ByteArray): ByteArray {
        val id = DnsWire.id(query)
        val body = query.copyOf().also { DnsWire.setId(it, 0) } // RFC 8484 §4.1: ID 0 aids HTTP caching
        val request = Request.Builder()
            .url(url)
            .header("Accept", DNS_MESSAGE)
            .post(body.toRequestBody(DNS_MEDIA))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val type = resp.header("Content-Type") ?: ""
            if (!type.startsWith(DNS_MESSAGE)) throw IOException("unexpected content type $type")
            val bytes = resp.body?.source()?.let { src ->
                src.request(MAX_RESPONSE + 1L)
                if (src.buffer.size > MAX_RESPONSE) throw IOException("response too large")
                src.buffer.readByteArray()
            } ?: throw IOException("empty body")
            if (bytes.size < DnsWire.HEADER || !DnsWire.isResponse(bytes)) throw IOException("malformed response")
            DnsWire.setId(bytes, id)
            return bytes
        }
    }

    companion object {
        const val DNS_MESSAGE = "application/dns-message"
        private val DNS_MEDIA = DNS_MESSAGE.toMediaType()
        private const val MAX_RESPONSE = 65_535

        fun baseClient(protector: SocketProtector, timeoutMs: Long = 5_000): OkHttpClient =
            OkHttpClient.Builder()
                .socketFactory(ProtectedSocketFactory(protector))
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(timeoutMs + 1_000, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }
}

/**
 * RFC 7858 DNS-over-TLS with connection reuse. Certificate *and* hostname are verified
 * (an SSLSocket does not check the hostname on its own, a classic DoT client bug).
 */
class DotTransport(
    private val hostname: String,
    private val addresses: List<InetAddress>,
    private val protector: SocketProtector,
    private val port: Int = 853,
    private val timeoutMs: Int = 5_000,
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val verifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    override val label: String = "tls://$hostname",
) : DnsTransport {
    override val encrypted = true

    private class Conn(val socket: SSLSocket, var lastUsed: Long)

    private val idle = ArrayDeque<Conn>()

    override fun exchange(query: ByteArray): ByteArray {
        // A pooled connection may have been closed by the server; retry once on a fresh one.
        val pooled = borrow()
        if (pooled != null) {
            try {
                return exchangeOn(pooled, query).also { release(pooled) }
            } catch (_: IOException) {
                runCatching { pooled.socket.close() }
            }
        }
        val fresh = open()
        try {
            return exchangeOn(fresh, query).also { release(fresh) }
        } catch (e: IOException) {
            runCatching { fresh.socket.close() }
            throw e
        }
    }

    private fun exchangeOn(c: Conn, query: ByteArray): ByteArray {
        val out = c.socket.outputStream
        val frame = ByteArray(query.size + 2)
        frame[0] = (query.size ushr 8).toByte(); frame[1] = query.size.toByte()
        System.arraycopy(query, 0, frame, 2, query.size)
        out.write(frame); out.flush()
        val input = DataInputStream(c.socket.inputStream)
        val len = input.readUnsignedShort()
        if (len < DnsWire.HEADER) throw IOException("short DoT response")
        val resp = ByteArray(len)
        input.readFully(resp)
        if (DnsWire.id(resp) != DnsWire.id(query)) throw IOException("DoT id mismatch")
        return resp
    }

    private fun open(): Conn {
        var last: IOException? = null
        for (addr in addresses) {
            val raw = Socket()
            try {
                protector.protect(raw)
                raw.connect(InetSocketAddress(addr, port), timeoutMs)
                raw.soTimeout = timeoutMs
                val ssl = sslFactory.createSocket(raw, hostname, port, true) as SSLSocket
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
                ssl.startHandshake()
                if (!verifier.verify(hostname, ssl.session)) {
                    ssl.close()
                    throw IOException("certificate does not match $hostname")
                }
                return Conn(ssl, System.currentTimeMillis())
            } catch (e: IOException) {
                runCatching { raw.close() }
                last = e
            }
        }
        throw last ?: IOException("no address for $hostname")
    }

    @Synchronized
    private fun borrow(): Conn? {
        val now = System.currentTimeMillis()
        while (idle.isNotEmpty()) {
            val c = idle.pollFirst()
            if (now - c.lastUsed < IDLE_MS && !c.socket.isClosed) return c
            runCatching { c.socket.close() }
        }
        return null
    }

    @Synchronized
    private fun release(c: Conn) {
        c.lastUsed = System.currentTimeMillis()
        if (idle.size < MAX_IDLE) idle.addFirst(c) else runCatching { c.socket.close() }
    }

    @Synchronized
    override fun close() {
        idle.forEach { runCatching { it.socket.close() } }
        idle.clear()
    }

    private companion object {
        const val IDLE_MS = 20_000L
        const val MAX_IDLE = 4
    }
}

/** Classic unencrypted DNS over UDP. Only used when the user explicitly allows it. */
class UdpTransport(
    private val server: InetAddress,
    private val protector: SocketProtector,
    private val port: Int = 53,
    private val timeoutMs: Int = 4_000,
    override val label: String = "udp://${server.hostAddress}",
) : DnsTransport {
    override val encrypted = false

    override fun exchange(query: ByteArray): ByteArray {
        DatagramSocket().use { s ->
            protector.protect(s)
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(query, query.size, server, port))
            val buf = ByteArray(4096)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                if (p.address != server || p.length < DnsWire.HEADER) continue
                val resp = buf.copyOf(p.length)
                if (DnsWire.id(resp) == DnsWire.id(query)) return resp
            }
        }
    }
}
