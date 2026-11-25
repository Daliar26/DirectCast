package com.directcast

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Передатчик (телефон). Захват экрана на Android 10+ обязан выполняться
 * в foreground‑сервисе типа mediaProjection — без него getMediaProjection()
 * падает с SecurityException, и трансляция не стартует.
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.directcast.START"
        const val ACTION_STOP = "com.directcast.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_W = "w"
        const val EXTRA_H = "h"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_AUDIO = "audio"
        private const val CHANNEL_ID = "directcast_cast"
        private const val NOTIF_ID = 4101

        @Volatile var running = false
        @Volatile var state = "Остановлено"
    }

    private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val s = session
            if (s != null) s.stop() else stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Сразу в foreground: иначе Android убьёт сервис, а захват экрана не разрешит.
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            AppLog.add("Не удалось запустить фоновый сервис: ${e.message}")
            state = "Ошибка запуска сервиса"
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = readProjectionIntent(intent)
        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        if (data == null || host.isBlank()) {
            AppLog.add("Нет данных для запуска (разрешение на захват или IP)")
            stopSelf()
            return START_NOT_STICKY
        }
        session?.stop()
        running = true
        acquireLocks()
        val s = Session(
            code, data, host,
            intent.getIntExtra(EXTRA_PORT, Proto.STREAM_PORT),
            intent.getIntExtra(EXTRA_W, 1280),
            intent.getIntExtra(EXTRA_H, 720),
            intent.getIntExtra(EXTRA_BITRATE, 4_000_000),
            intent.getBooleanExtra(EXTRA_AUDIO, true)
        )
        session = s
        s.start()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readProjectionIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_DATA)

    @Suppress("DEPRECATION")
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Трансляция экрана", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), flags
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Остановить", stopIntent
        ).build()
        val notification = builder
            .setContentTitle("DirectCast")
            .setContentText("Идёт трансляция экрана на проектор")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DirectCast:cast").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "DirectCast:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        session?.stop()
        releaseLocks()
        running = false
        super.onDestroy()
    }

    private inner class Session(
        private val code: Int,
        private val data: Intent,
        private val host: String,
        private val port: Int,
        private val width: Int,
        private val height: Int,
        private val bitrate: Int,
        private val audioEnabled: Boolean
    ) {
        @Volatile private var active = true
        @Volatile private var socket: Socket? = null
        private val sendLock = Any()
        private var codec: MediaCodec? = null
        private var projection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var audioRec: AudioRecord? = null

        fun start() {
            thread(name = "DC-sender", isDaemon = true) { run() }
        }

        fun stop() {
            active = false
            try { socket?.close() } catch (_: Exception) {}
        }

        private fun run() {
            try {
                state = "Подготовка…"
                AppLog.add("Сеть телефона:\n${NetUtil.describe()}")

                // 1. Захват экрана. Токен действует недолго, поэтому берём его первым делом.
                val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val proj = manager.getMediaProjection(code, data)
                    ?: throw IOException("Android не выдал доступ к захвату экрана")
                projection = proj
                // Android 14+: callback обязателен до создания VirtualDisplay.
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (active) AppLog.add("Захват экрана остановлен системой")
                        stop()
                    }
                }, Handler(Looper.getMainLooper()))

                // 2. Сеть: подключение к проектору и рукопожатие.
                state = "Подключение к $host:$port…"
                AppLog.add("Подключаюсь к $host:$port …")
                val s = NetUtil.connect(applicationContext, host, port, 5000) { socket = it }
                s.tcpNoDelay = true
                s.sendBufferSize = 256 * 1024
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                out.writeInt(Proto.MAGIC)
                out.writeInt(Proto.VERSION)
                out.writeInt(width)
                out.writeInt(height)
                out.flush()
                s.soTimeout = 5000
                when (val ack = s.getInputStream().read()) {
                    Proto.ACK_OK -> {}
                    Proto.ACK_BAD_VERSION ->
                        throw IOException("версия приложения на проекторе отличается — установите один и тот же APK на оба устройства")
                    else -> throw IOException("проектор не подтвердил соединение (ответ $ack)")
                }
                s.soTimeout = 0
                AppLog.add("Проектор подтвердил соединение")

                // 3. Кодер и виртуальный экран.
                val (enc, inputSurface) = startEncoder()
                codec = enc
                val dpi = resources.displayMetrics.densityDpi
                virtualDisplay = proj.createVirtualDisplay(
                    "DirectCast", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null
                ) ?: throw IOException("не удалось создать виртуальный экран")
                AppLog.add("Трансляция запущена (${width}x$height, ${bitrate / 1_000_000} Мбит/с, H.264)")
                if (audioEnabled) startAudio(proj, out)

                // 4. Отправка кадров.
                val info = MediaCodec.BufferInfo()
                var lastSend = System.currentTimeMillis()
                var windowStart = lastSend
                var windowBytes = 0L
                while (active) {
                    val idx = enc.dequeueOutputBuffer(info, 100_000)
                    if (idx >= 0) {
                        val buf = enc.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            buf.get(bytes)
                            val type = when {
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 -> Proto.TYPE_CONFIG
                                (info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0 -> Proto.TYPE_KEY
                                else -> Proto.TYPE_DELTA
                            }
                            writeFrame(out, type, info.presentationTimeUs, bytes)
                            windowBytes += bytes.size
                            lastSend = System.currentTimeMillis()
                        }
                        enc.releaseOutputBuffer(idx, false)
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastSend > 1000) {
                        writeFrame(out, Proto.TYPE_PING, 0L, ByteArray(0))
                        lastSend = now
                    }
                    if (now - windowStart >= 2000) {
                        val mbit = windowBytes * 8.0 / ((now - windowStart) * 1000.0)
                        state = "Трансляция идёт на $host (${"%.1f".format(mbit)} Мбит/с)"
                        windowStart = now
                        windowBytes = 0
                    }
                }
                AppLog.add("Трансляция остановлена")
            } catch (e: Exception) {
                if (active) AppLog.add("Ошибка: ${NetUtil.explain(e, host)}")
            } finally {
                active = false
                cleanup()
                // Если уже запущена новая сессия, её состояние не трогаем.
                if (session === this) {
                    state = "Остановлено"
                    running = false
                    try { stopForeground(true) } catch (_: Exception) {}
                    stopSelf()
                }
            }
        }

        private fun buildFormat(full: Boolean): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if (full) {
                    // Повтор кадра, когда экран статичен, и SPS/PPS в каждом ключевом кадре.
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
                    setInteger("prepend-sps-pps-to-idr-frames", 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }

        /** Запускает кодер; если устройство не принимает расширенные параметры — повторяет с минимальными. */
        private fun startEncoder(): Pair<MediaCodec, Surface> {
            var lastError: Exception? = null
            for (full in booleanArrayOf(true, false)) {
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    enc.configure(buildFormat(full), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val surf = enc.createInputSurface()
                    enc.start()
                    return Pair(enc, surf)
                } catch (e: Exception) {
                    lastError = e
                    try { enc.release() } catch (_: Exception) {}
                }
            }
            throw IOException("кодер H.264 не запустился: ${lastError?.message}")
        }

        /** Захват звука приложений (Android 10+). Необязателен: без него передаётся только картинка. */
        private fun startAudio(proj: MediaProjection, out: DataOutputStream) {
            if (Build.VERSION.SDK_INT < 29) {
                AppLog.add("Звук: нужен Android 10 или новее — передаётся только картинка")
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                AppLog.add("Звук выключен: нет разрешения на запись звука — передаётся только картинка")
                return
            }
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Proto.AUDIO_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val chunk = Proto.AUDIO_RATE / 50 * 4   // 20 мс, 16 бит, стерео
                val min = AudioRecord.getMinBufferSize(
                    Proto.AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
                val rec = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(maxOf(min, chunk * 8))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    AppLog.add("Звук недоступен: не удалось открыть захват звука — передаётся только картинка")
                    return
                }
                rec.startRecording()
                audioRec = rec
                AppLog.add("Звук включён (звук приложений; защищённые сервисы могут его запрещать)")
                thread(name = "DC-audio", isDaemon = true) {
                    val buf = ByteArray(chunk)
                    try {
                        while (active) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n < 0) break
                            if (n == 0) continue
                            writeFrame(out, Proto.TYPE_AUDIO, 0L, if (n == buf.size) buf else buf.copyOf(n))
                        }
                    } catch (e: Exception) {
                        if (active) AppLog.add("Звук остановлен: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                AppLog.add("Звук недоступен: ${e.message} — передаётся только картинка")
            }
        }

        private fun writeFrame(out: DataOutputStream, type: Int, pts: Long, data: ByteArray) {
            synchronized(sendLock) {
                out.writeByte(type)
                out.writeLong(pts)
                out.writeInt(data.size)
                if (data.isNotEmpty()) out.write(data)
                out.flush()
            }
        }

        private fun cleanup() {
            try { audioRec?.stop() } catch (_: Exception) {}
            try { audioRec?.release() } catch (_: Exception) {}
            try { virtualDisplay?.release() } catch (_: Exception) {}
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { projection?.stop() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            audioRec = null
            virtualDisplay = null
            codec = null
            projection = null
            socket = null
        }
    }
}
