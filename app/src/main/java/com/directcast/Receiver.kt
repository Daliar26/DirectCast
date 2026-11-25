package com.directcast

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Приёмник (проектор). Слушает TCP-порт, отвечает на UDP-запросы поиска,
 * декодирует H.264 прямо на Surface и проигрывает звук.
 */
class StreamReceiver(
    context: Context,
    private val surface: Surface,
    private val deviceName: String,
    private val log: (String) -> Unit,
    private val onStreaming: (Boolean, Int, Int) -> Unit
) {
    private val ctx = context.applicationContext
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    @Volatile private var discovery: DatagramSocket? = null
    private val conns = HashSet<Socket>()
    private var active: Socket? = null

    fun start() {
        if (running) return
        running = true
        thread(name = "DC-discovery", isDaemon = true) { discoveryLoop() }
        thread(name = "DC-accept", isDaemon = true) { acceptLoop() }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { discovery?.close() } catch (_: Exception) {}
        synchronized(lock) {
            for (c in conns) {
                try { c.close() } catch (_: Exception) {}
            }
            conns.clear()
            active = null
        }
    }

    // ---------- автопоиск ----------

    private fun discoveryLoop() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(Proto.DISCOVERY_PORT))
            s.broadcast = true
            discovery = s
            if (!running) { s.close(); return }
            val buf = ByteArray(256)
            val reply = "${Proto.REPLY}|$deviceName|${Proto.STREAM_PORT}".toByteArray()
            val asked = HashSet<String>()
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                s.receive(p)
                val text = String(p.data, 0, p.length)
                if (text.startsWith(Proto.QUERY)) {
                    val who = p.address.hostAddress ?: "?"
                    if (asked.add(who)) log("Телефон $who ищет проектор — отвечаю")
                    reply(p.address, p.port, reply)
                }
            }
        } catch (e: Exception) {
            if (running) log("Автопоиск недоступен: ${e.message}. Вводите IP на телефоне вручную.")
        }
    }

    private fun reply(to: InetAddress, port: Int, bytes: ByteArray) {
        try {
            DatagramSocket().use { s ->
                to.hostAddress?.let { NetUtil.bind(ctx, it, s) }
                s.send(DatagramPacket(bytes, bytes.size, to, port))
            }
        } catch (_: Exception) {
        }
    }

    // ---------- приём соединений ----------

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(Proto.STREAM_PORT))
            server = ss
            if (!running) { ss.close(); return }
            log("Жду телефон на TCP-порту ${Proto.STREAM_PORT}")
            while (running) {
                val c = ss.accept()
                synchronized(lock) { conns.add(c) }
                thread(name = "DC-client", isDaemon = true) { serve(c) }
            }
        } catch (e: Exception) {
            if (running) log("Ошибка приёмника: ${e.message}")
        }
    }

    private fun serve(c: Socket) {
        try {
            handle(c)
        } catch (e: Exception) {
            if (running) log("Сессия завершена: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { c.close() } catch (_: Exception) {}
            val wasActive = synchronized(lock) {
                conns.remove(c)
                val a = active === c
                if (a) active = null
                a
            }
            if (wasActive) onStreaming(false, 0, 0)
        }
    }

    private fun handle(c: Socket) {
        c.tcpNoDelay = true
        c.soTimeout = 8000
        val input = DataInputStream(BufferedInputStream(c.getInputStream(), 64 * 1024))
        val remote = c.inetAddress?.hostAddress ?: "?"

        val magic = try {
            input.readInt()
        } catch (e: SocketTimeoutException) {
            log("Подключение $remote молчит — закрыто")
            return
        } catch (e: IOException) {
            log("Проверка связи с $remote: порт доступен")
            return
        }
        if (magic != Proto.MAGIC) {
            log("Подключение $remote не от DirectCast — закрыто")
            return
        }
        val version = input.readInt()
        val width = input.readInt()
        val height = input.readInt()
        val out = c.getOutputStream()
        if (version != Proto.VERSION) {
            log("Версия приложения на телефоне ($version) не совпадает с версией на проекторе (${Proto.VERSION}). Установите один и тот же APK на оба устройства.")
            try { out.write(Proto.ACK_BAD_VERSION); out.flush() } catch (_: Exception) {}
            return
        }
        if (width == 0 && height == 0) {   // проверка связи с телефона
            out.write(Proto.ACK_OK)
            out.flush()
            log("Проверка связи с $remote: DirectCast отвечает")
            return
        }
        if (width !in 16..4096 || height !in 16..4096) {
            log("Телефон $remote прислал неверный размер картинки ${width}x$height")
            return
        }
        out.write(Proto.ACK_OK)
        out.flush()
        // Новая трансляция заменяет прежнюю; простые проверки связи её не трогают.
        synchronized(lock) {
            val old = active
            active = c
            if (old != null && old !== c) {
                try { old.close() } catch (_: Exception) {}
            }
        }
        log("Телефон $remote подключён (${width}x$height), жду видео…")

        var decoder: Decoder? = null
        var audio: AudioOut? = null
        var audioFailed = false
        var frames = 0L
        try {
            while (running) {
                val type = input.readUnsignedByte()
                val pts = input.readLong()
                val size = input.readInt()
                if (size < 0 || size > 8_000_000) throw IOException("повреждён поток (размер кадра $size)")
                if (size == 0) continue   // keep-alive
                val data = ByteArray(size)
                input.readFully(data)
                when (type) {
                    Proto.TYPE_CONFIG -> {
                        decoder?.release()
                        decoder = null
                        decoder = newDecoder(width, height, data)
                    }
                    Proto.TYPE_KEY, Proto.TYPE_DELTA -> {
                        // Если SPS/PPS не пришли отдельно, берём их из ключевого кадра.
                        if (decoder == null && type == Proto.TYPE_KEY) decoder = newDecoder(width, height, data)
                        val d = decoder
                        if (d != null) {
                            d.queue(data, pts)
                            frames++
                            if (frames == 1L) log("Первый кадр получен, жду вывод на экран…")
                        }
                    }
                    Proto.TYPE_AUDIO -> {
                        if (audio == null && !audioFailed) {
                            try {
                                audio = AudioOut()
                            } catch (e: Exception) {
                                audioFailed = true
                                log("Звук на проекторе недоступен: ${e.message}")
                            }
                        }
                        audio?.write(data)
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            log("Нет данных от телефона 8 с — соединение закрыто")
        } catch (e: EOFException) {
            log("Телефон отключился")
        } finally {
            decoder?.release()
            audio?.release()
        }
    }

    private fun newDecoder(w: Int, h: Int, data: ByteArray): Decoder = try {
        Decoder(w, h, data)
    } catch (e: Exception) {
        log("Не удалось запустить декодер H.264: ${e.message}")
        throw e
    }

    /** Один сеанс декодирования. */
    private inner class Decoder(private val w: Int, private val h: Int, csdSource: ByteArray) {
        private val codec: MediaCodec
        @Volatile private var alive = true
        @Volatile private var shown = false
        private val outThread: Thread

        init {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            val (sps, pps) = findCsd(csdSource)
            if (sps != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            if (pps != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            format.setInteger("low-latency", 1)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                c.configure(format, surface, null, 0)
                c.start()
            } catch (e: Exception) {
                try { c.release() } catch (_: Exception) {}
                throw e
            }
            codec = c
            outThread = thread(name = "DC-decoder-out", isDaemon = true) { drain() }
        }

        private fun drain() {
            val info = MediaCodec.BufferInfo()
            while (alive) {
                try {
                    val idx = codec.dequeueOutputBuffer(info, 20_000)
                    if (idx >= 0) {
                        codec.releaseOutputBuffer(idx, true)
                        if (!shown) {
                            shown = true
                            log("Видео идёт (${w}x$h)")
                            onStreaming(true, w, h)
                        }
                    }
                } catch (e: IllegalStateException) {
                    break
                }
            }
        }

        fun queue(data: ByteArray, ptsUs: Long) {
            var tries = 0
            while (alive && running) {
                val idx = try { codec.dequeueInputBuffer(50_000) } catch (e: IllegalStateException) { return }
                if (idx < 0) {
                    if (++tries > 40) return   // декодер завис — пропускаем кадр, не блокируем сеть
                    continue
                }
                val buf = codec.getInputBuffer(idx)
                if (buf == null || data.size > buf.capacity()) {
                    codec.queueInputBuffer(idx, 0, 0, ptsUs, 0)
                    return
                }
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
                return
            }
        }

        fun release() {
            alive = false
            try { outThread.join(500) } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    /** Воспроизведение PCM 16 бит стерео. Запись неблокирующая: при переполнении звук пропускается, видео не страдает. */
    private class AudioOut {
        private val track: AudioTrack

        init {
            val channel = AudioFormat.CHANNEL_OUT_STEREO
            val enc = AudioFormat.ENCODING_PCM_16BIT
            val min = AudioTrack.getMinBufferSize(Proto.AUDIO_RATE, channel, enc)
            val size = maxOf(min * 2, Proto.AUDIO_RATE * 4 / 5)   // около 200 мс
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(enc)
                        .setSampleRate(Proto.AUDIO_RATE)
                        .setChannelMask(channel)
                        .build()
                )
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
        }

        fun write(b: ByteArray) {
            try { track.write(b, 0, b.size, AudioTrack.WRITE_NON_BLOCKING) } catch (_: Exception) {}
        }

        fun release() {
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    // ---------- разбор Annex‑B ----------

    private fun startCodeLen(b: ByteArray, i: Int): Int {
        if (i + 3 <= b.size && b[i] == 0.toByte() && b[i + 1] == 0.toByte()) {
            if (b[i + 2] == 1.toByte()) return 3
            if (i + 4 <= b.size && b[i + 2] == 0.toByte() && b[i + 3] == 1.toByte()) return 4
        }
        return 0
    }

    /** Делит Annex‑B поток на NAL‑блоки вместе со стартовыми кодами. */
    private fun splitAnnexB(b: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i < b.size) {
            val n = startCodeLen(b, i)
            if (n > 0) { starts.add(i); i += n } else i++
        }
        val out = ArrayList<ByteArray>()
        for (k in starts.indices) {
            val end = if (k + 1 < starts.size) starts[k + 1] else b.size
            out.add(b.copyOfRange(starts[k], end))
        }
        return out
    }

    /** Находит SPS (тип 7) и PPS (тип 8) в потоке. */
    private fun findCsd(b: ByteArray): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexB(b)) {
            val n = startCodeLen(nal, 0)
            if (n == 0 || nal.size <= n) continue
            when (nal[n].toInt() and 0x1F) {
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
            }
        }
        return Pair(sps, pps)
    }
}
