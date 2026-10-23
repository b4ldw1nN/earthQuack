package io.earthquack.app.tailscale

import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * A one-shot TCP relay that speaks enough SOCKS5 to reach a tailnet host
 * through the embedded node's SOCKS port (the MINA SSHD leg).
 *
 * ## Why a relay exists at all
 *
 * Apache MINA SSHD has no client-side proxy support — sshd issue #309 states
 * it outright: "users have to write their own code." The roadmap sketched
 * `Socks5ProxyData`, but that class does not exist in sshd-core 2.20.0 (its
 * only SOCKS code is `common/forward/SocksProxy`, the *server* side used for
 * `-D` dynamic forwarding). Two routes existed:
 *
 *  1. JGit's approach — build the `IoSession` by hand, do the SOCKS handshake
 *     on it, then construct sshd's `DefaultClientSession` around it. It reaches
 *     into sshd-internal classes and the "divert messages until the proxy
 *     protocol finishes" plumbing of `ClientProxyConnector`, which upstream
 *     does not implement for SOCKS.
 *  2. This one — the ProxyCommand pattern from OpenSSH itself: a local TCP
 *     listener that performs the SOCKS5 handshake on the caller's behalf and
 *     then pipes bytes. sshd connects to `127.0.0.1` as it always does; no
 *     sshd internals, nothing to break on an sshd upgrade.
 *
 * Route 2 is what ships here. It costs two pump threads and a loopback copy
 * per SSH session — negligible next to SSH transport crypto — and it is
 * testable end-to-end on the JVM against a fake SOCKS server.
 *
 * ## Protocol scope
 *
 * RFC 1928, no-auth only (`0x00`): tsnet's SOCKS listener (x/net/proxy style)
 * accepts anonymous loopback clients. CONNECT with ATYP=DOMAINNAME always —
 * the target is a 100.x literal or a MagicDNS name, and letting the *server*
 * resolve means MagicDNS never depends on the device's system resolver.
 *
 * ## Lifetime
 *
 * One [Socks5Relay] serves exactly one outbound connection (the accept loop
 * closes the listener after the first client — sshd dials once per session).
 * After a successful handshake the relay keeps pumping until either side
 * closes, i.e. for the lifetime of the SSH session; [close] is for the
 * failure paths where the connection never materialised.
 */
class Socks5Relay private constructor(
    private val proxyHost: String,
    private val proxyPort: Int,
    val targetHost: String,
    val targetPort: Int,
) : Closeable {

    private val server: ServerSocket =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))

    /** The loopback port sshd should connect to instead of the real host. */
    val localPort: Int get() = server.localPort

    /** The live sockets, so [close] can tear down an in-flight session. */
    private val clientRef = AtomicReference<Socket?>()
    private val proxyRef = AtomicReference<Socket?>()

    @Volatile
    private var closed = false

    private val handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS

    /** Binds the loopback listener and starts the accept thread. */
    fun start(): Socks5Relay {
        val accept = Thread({ acceptAndRelay() }, "tailscale-relay-accept-$localPort")
        accept.isDaemon = true
        accept.start()
        return this
    }

    private fun acceptAndRelay() {
        val client = try {
            server.accept()
        } catch (e: IOException) {
            // close() before a client arrived — the normal shutdown path.
            if (!closed) Log.w(TAG, "relay accept failed", e)
            return
        } finally {
            // Single-use: one SSH connection per relay.
            runCatching { server.close() }
        }

        var proxy: Socket? = null
        try {
            proxy = Socket().also { it.connect(InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT_MS) }
            proxyRef.set(proxy)
            proxy.soTimeout = handshakeTimeoutMs
            socks5Connect(proxy)
            proxy.soTimeout = 0 // session traffic has no handshake deadline
            clientRef.set(client)
            pump(client, proxy)
        } catch (e: Exception) {
            Log.w(TAG, "relay to $targetHost:$targetPort failed: ${e.message}")
            runCatching { client.close() }
            runCatching { proxy?.close() }
        }
    }

    /**
     * The RFC 1928 handshake, client role:
     * greeting `05 01 00` → method select → CONNECT with the target
     * as a domain name. Throws on anything other than success, so the caller
     * tears the sockets down and sshd sees a plain connection failure — the
     * factory's existing classifier then reports "connection refused" style
     * errors honestly instead of hanging.
     */
    private fun socks5Connect(proxy: Socket) {
        val out = proxy.getOutputStream()
        val input = proxy.getInputStream()

        out.write(byteOf(0x05, 0x01, 0x00)) // VER, NMETHODS, NO-AUTH
        out.flush()
        val method = readExact(input, 2)
        if (method[0] != 0x05.toByte() || method[1] != 0x00.toByte()) {
            throw IOException("socks5 server rejected no-auth (reply ${method.joinToString()})")
        }

        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.isEmpty() || hostBytes.size > 255) {
            throw IOException("target host length ${hostBytes.size} not encodable in socks5")
        }
        val request = ByteArray(4 + 1 + hostBytes.size + 2)
        request[0] = 0x05 // VER
        request[1] = 0x01 // CMD = CONNECT
        request[2] = 0x00 // RSV
        request[3] = 0x03 // ATYP = DOMAINNAME — server-side (MagicDNS) resolution
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, 5)
        request[request.size - 2] = ((targetPort shr 8) and 0xff).toByte()
        request[request.size - 1] = (targetPort and 0xff).toByte()
        out.write(request)
        out.flush()

        val header = readExact(input, 4)
        if (header[0] != 0x05.toByte()) throw IOException("bad socks5 reply version ${header[0]}")
        val replyCode = header[1].toInt() and 0xff
        if (replyCode != 0x00) throw IOException("socks5 CONNECT refused, code $replyCode")
        // BND.ADDR + BND.PORT — variable length, discarded.
        val boundAddressLength = when (header[3].toInt() and 0xff) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> (readExact(input, 1)[0].toInt() and 0xff)
            else -> throw IOException("unknown socks5 ATYP ${header[3]} in reply")
        }
        readExact(input, boundAddressLength + 2)
    }

    /** Two threads, one per direction; either ending tears the other down. */
    private fun pump(client: Socket, proxy: Socket) {
        val through = Thread(
            { copyUntilEof(proxy.getInputStream(), client.getOutputStream(), client, proxy) },
            "tailscale-relay-through-$localPort",
        )
        through.isDaemon = true
        through.start()
        Thread(
            { copyUntilEof(client.getInputStream(), proxy.getOutputStream(), client, proxy) },
            "tailscale-relay-back-$localPort",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun copyUntilEof(
        input: InputStream,
        output: OutputStream,
        vararg sockets: Socket,
    ) {
        try {
            input.copyTo(output)
        } catch (_: IOException) {
            // Socket closed mid-pump — the session ended; not an error.
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        clientRef.getAndSet(null)?.let { runCatching { it.close() } }
        proxyRef.getAndSet(null)?.let { runCatching { it.close() } }
    }

    /** [readExact] that fails loudly on a short read — a torn handshake. */
    private fun readExact(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) throw IOException("socks5 handshake ended after $off of $length bytes")
            off += n
        }
        return buf
    }

    private fun byteOf(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    companion object {
        private const val TAG = "Socks5Relay"
        private const val LOOPBACK = "127.0.0.1"

        /** Proxy handshake deadline — everything is local, so this is generous. */
        const val HANDSHAKE_TIMEOUT_MS = 10_000

        /** TCP connect to the SOCKS port itself (also local). */
        const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * Binds a free loopback port, starts relaying, returns the relay.
         * The caller hands [localPort] to sshd as the connect target.
         */
        fun start(
            proxyHost: String,
            proxyPort: Int,
            targetHost: String,
            targetPort: Int,
        ): Socks5Relay = Socks5Relay(proxyHost, proxyPort, targetHost, targetPort).start()
    }
}
