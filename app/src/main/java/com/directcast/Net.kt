package com.directcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Параметры протокола. Телефон и проектор должны быть одной версии приложения. */
object Proto {
    const val STREAM_PORT = 5000      // TCP: видео и звук
    const val DISCOVERY_PORT = 5001   // UDP: автопоиск проектора
    const val MAGIC = 0x44435354      // "DCST"
    const val VERSION = 3

    const val TYPE_CONFIG = 0         // SPS/PPS
    const val TYPE_KEY = 1            // ключевой кадр
    const val TYPE_DELTA = 2          // обычный кадр
    const val TYPE_PING = 3           // keep-alive
    const val TYPE_AUDIO = 4          // PCM 16 бит, стерео

    const val ACK_OK = 1
    const val ACK_BAD_VERSION = 2

    const val AUDIO_RATE = 48000
    const val AUDIO_CHANNELS = 2

    const val QUERY = "DIRECTCAST?"
    const val REPLY = "DIRECTCAST!"
}

/** Журнал событий, который показывается на экране (на проекторе нет logcat). */
object AppLog {
    private val lines = java.util.ArrayDeque<String>()

    @Synchronized
    fun add(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines.addLast("$time  $msg")
        while (lines.size > 80) lines.removeFirst()
    }

    @Synchronized
    fun text(last: Int = 20): String = lines.toList().takeLast(last).joinToString("\n")
}

data class LocalAddr(val ifName: String, val ip: String, val prefix: Int, val broadcast: String?)

data class FoundReceiver(val ip: String, val name: String, val port: Int)

object NetUtil {
    // Мобильный интернет, туннели и служебные интерфейсы — не локальная сеть
    private val skipPrefixes = listOf("rmnet", "ccmni", "dummy", "tun", "p2p", "v4-", "clat", "ifb", "sit", "ip6")

    fun localAddresses(): List<LocalAddr> {
        val out = ArrayList<LocalAddr>()
        val ifs = try { NetworkInterface.getNetworkInterfaces() } catch (_: Exception) { null } ?: return out
        for (nif in Collections.list(ifs)) {
            try {
                val name = nif.name ?: continue
                if (!nif.isUp || nif.isLoopback) continue
                if (skipPrefixes.any { name.startsWith(it) }) continue
                for (ia in nif.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val host = a.hostAddress ?: continue
                        out.add(LocalAddr(name, host, ia.networkPrefixLength.toInt(), ia.broadcast?.hostAddress))
                    }
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun describe(): String {
        val a = localAddresses()
        return if (a.isEmpty()) "нет подключения к Wi‑Fi / точке доступа"
        else a.joinToString("\n") { "• ${it.ip}/${it.prefix}  (${it.ifName})" }
    }

    private val ipRegex = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    fun validIp(s: String): Boolean = ipRegex.matches(s)

    private fun sameSubnet(a: InetAddress, b: InetAddress, prefix: Int): Boolean {
        val x = a.address
        val y = b.address
        if (x.size != y.size || prefix <= 0) return false
        var bits = prefix
        var i = 0
        while (bits > 0 && i < x.size) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    /**
     * Находит сеть Wi‑Fi/Ethernet, в которой лежит адрес [ip].
     *
     * Это важно: если у Wi‑Fi нет интернета, Android по умолчанию гонит трафик приложений
     * через мобильную сеть, и адреса вида 192.168.x.x становятся недостижимы. Привязка
     * сокета к «своей» сети решает проблему. Для точки доступа самого телефона отдельной
     * сети нет — там работает обычная маршрутизация (вернётся null).
     */
    @Suppress("DEPRECATION")
    fun networkFor(ctx: Context, ip: String): Network? {
        return try {
            if (!validIp(ip)) return null
            val target = InetAddress.getByName(ip)
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n)
                if (caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                ) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && sameSubnet(addr, target, la.prefixLength)) return n
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun bind(ctx: Context, ip: String, s: Socket) {
        try { networkFor(ctx, ip)?.bindSocket(s) } catch (_: Exception) {}
    }

    fun bind(ctx: Context, ip: String, s: DatagramSocket) {
        try { networkFor(ctx, ip)?.bindSocket(s) } catch (_: Exception) {}
    }

    /** TCP-подключение к [host] через правильную сеть. [onCreated] позволяет заранее запомнить сокет, чтобы его можно было закрыть. */
    fun connect(ctx: Context, host: String, port: Int, timeoutMs: Int, onCreated: (Socket) -> Unit = {}): Socket {
        val s = Socket()
        onCreated(s)
        try {
            bind(ctx, host, s)
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Полная проверка связи: TCP-подключение и рукопожатие приложения.
     * Возвращает null, если всё хорошо, иначе понятный текст проблемы.
     */
    fun probe(ctx: Context, host: String): String? {
        return try {
            val s = connect(ctx, host, Proto.STREAM_PORT, 4000)
            try {
                s.soTimeout = 4000
                val out = DataOutputStream(s.getOutputStream())
                out.writeInt(Proto.MAGIC)
                out.writeInt(Proto.VERSION)
                out.writeInt(0)   // 0x0 — признак проверки связи, видео не начинается
                out.writeInt(0)
                out.flush()
                when (s.getInputStream().read()) {
                    Proto.ACK_OK -> null
                    Proto.ACK_BAD_VERSION ->
                        "на проекторе другая версия DirectCast — установите один и тот же APK на оба устройства"
                    else -> "порт открыт, но это не DirectCast (или приложение на проекторе старой версии)"
                }
            } finally {
                try { s.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            explain(e, host)
        }
    }

    /** Человеческое объяснение сетевой ошибки. */
    fun explain(e: Throwable, host: String): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            e is SecurityException ->
                "Android не разрешил захват экрана ($msg)"
            msg.contains("ECONNREFUSED") ->
                "проектор $host отклонил соединение: приложение не слушает порт ${Proto.STREAM_PORT}. " +
                    "На проекторе откройте DirectCast → «Приёмник» и оставьте этот экран открытым."
            msg.contains("ENETUNREACH") || msg.contains("EHOSTUNREACH") || e is NoRouteToHostException ->
                "нет маршрута до $host. Телефон и проектор должны быть в одной сети " +
                    "(проектор подключён к точке доступа телефона). Отключите VPN, если он включён."
            e is SocketTimeoutException || msg.contains("ETIMEDOUT") ->
                "нет ответа от $host. Проверьте IP, что оба устройства в одной сети, " +
                    "что на проекторе открыт «Приёмник» и что в роутере не включена изоляция клиентов."
            e is UnknownHostException ->
                "неверный адрес $host"
            else -> msg
        }
    }
}

object Discovery {
    /**
     * Ищет приёмники: на каждом локальном интерфейсе шлёт UDP-запрос широковещательно
     * и каждому адресу подсети /24 — так поиск работает и там, где broadcast фильтруется.
     */
    fun discover(ctx: Context, durationMs: Int, isCancelled: () -> Boolean, onFound: (FoundReceiver) -> Unit) {
        val addrs = NetUtil.localAddresses()
        if (addrs.isEmpty()) return
        val own = addrs.map { it.ip }.toSet()
        val seen = HashSet<String>()
        val workers = addrs.map { a ->
            thread(name = "DC-scan-${a.ifName}", isDaemon = true) {
                scan(ctx, a, own, durationMs, isCancelled) { f ->
                    val isNew = synchronized(seen) { seen.add(f.ip) }
                    if (isNew) onFound(f)
                }
            }
        }
        for (w in workers) {
            try { w.join(durationMs + 1500L) } catch (_: InterruptedException) {}
        }
    }

    private fun scan(
        ctx: Context, a: LocalAddr, own: Set<String>, durationMs: Int,
        isCancelled: () -> Boolean, onFound: (FoundReceiver) -> Unit
    ) {
        val sock = try { DatagramSocket() } catch (_: Exception) { return }
        try {
            NetUtil.bind(ctx, a.ip, sock)
            sock.broadcast = true
            sock.soTimeout = 150
            val query = Proto.QUERY.toByteArray()

            fun sendTo(ip: String) {
                try {
                    sock.send(DatagramPacket(query, query.size, InetAddress.getByName(ip), Proto.DISCOVERY_PORT))
                } catch (_: Exception) {
                }
            }

            fun sendAll() {
                sendTo("255.255.255.255")
                a.broadcast?.let { sendTo(it) }
                val base = a.ip.substringBeforeLast('.')
                sendTo("$base.255")
                for (i in 1..254) {
                    val ip = "$base.$i"
                    if (ip !in own) sendTo(ip)
                }
            }

            val start = System.currentTimeMillis()
            val buf = ByteArray(512)
            var resent = 0
            sendAll()
            while (!isCancelled() && System.currentTimeMillis() - start < durationMs) {
                val elapsed = System.currentTimeMillis() - start
                if (resent < 2 && elapsed > 900L * (resent + 1)) {
                    resent++
                    sendAll()
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val text = String(p.data, 0, p.length)
                    if (text.startsWith(Proto.REPLY)) {
                        val ip = p.address.hostAddress ?: continue
                        val parts = text.split("|")
                        val name = parts.getOrNull(1)?.ifBlank { null } ?: "Проектор"
                        val port = parts.getOrNull(2)?.toIntOrNull() ?: Proto.STREAM_PORT
                        onFound(FoundReceiver(ip, name, port))
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
