package com.directcast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val requestCapture = 7101
    private var receiver: H264Receiver? = null
    private var sender: H264Sender? = null
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var ipInput: EditText
    private var surfaceView: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun base(title: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFF101820.toInt())
        }
        val heading = TextView(this).apply {
            text = title; textSize = 26f; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(heading)
        status = TextView(this).apply {
            text = "Готово"; textSize = 15f; setTextColor(0xFFB8D8E8.toInt())
            setPadding(0, 18, 0, 18)
        }
        root.addView(status)
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) {
        root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
    }

    private fun showHome() {
        receiver?.stop(); receiver = null
        sender?.stop(); sender = null
        base("DirectCast")
        val info = TextView(this).apply {
            text = "Беспроводная передача экрана и видео по локальной Wi‑Fi сети.\n\nСначала включи точку доступа на Redmi 12 и подключи к ней проектор A10."
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 16, 0, 16)
        }
        root.addView(info)
        button("Приёмник — проектор") { showReceiver() }
        button("Передатчик — телефон") { showSender() }
        button("Как подключить") {
            status.text = "1. Включи точку доступа на телефоне.\n2. Подключи проектор к этой сети.\n3. Открой DirectCast на обоих устройствах."
        }
    }

    private fun showReceiver() {
        base("DirectCast • Приёмник")
        val ip = localIp()
        root.addView(TextView(this).apply {
            text = "IP проектора: $ip\nПорт видео: 5000\n\nВведи этот IP в приложении на телефоне."
            textSize = 17f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 12, 0, 12)
        })
        val preview = SurfaceView(this)
        surfaceView = preview
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        button("Начать приём") {
            val s = preview.holder.surface
            receiver?.stop()
            receiver = H264Receiver(s) { msg -> runOnUiThread { status.text = msg } }
            receiver?.start()
            status.text = "Ожидание потока на UDP 5000…"
        }
        button("Остановить") { receiver?.stop(); receiver = null; status.text = "Приём остановлен" }
        button("На главный экран") { showHome() }
    }

    private fun showSender() {
        base("DirectCast • Передатчик")
        ipInput = EditText(this).apply {
            hint = "IP проектора, например 192.168.43.120"
            setSingleLine(true); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFAAAAAA.toInt())
        }
        root.addView(ipInput)
        root.addView(TextView(this).apply {
            text = "Начальный режим: 720p / 30 FPS / H.264"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
        })
        button("Начать трансляцию экрана") {
            val ip = ipInput.text.toString().trim()
            if (ip.isBlank()) { status.text = "Сначала введи IP проектора"; return@button }
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
            status.text = "Подтверди захват экрана в системном окне Android."
        }
        button("На главный экран") { showHome() }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture) {
            if (resultCode == RESULT_OK && data != null) {
                val ip = ipInput.text.toString().trim()
                sender?.stop()
                sender = H264Sender(this, resultCode, data, ip) { msg -> runOnUiThread { status.text = msg } }
                sender?.start()
            } else status.text = "Захват экрана не разрешён."
        }
    }

    private fun localIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val a = addresses.nextElement()
                    if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {}
        return "Не удалось определить IP"
    }

    override fun onDestroy() {
        receiver?.stop(); sender?.stop()
        super.onDestroy()
    }
}

private class H264Sender(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val host: String,
    private val report: (String) -> Unit
) {
    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var socket: DatagramSocket? = null
    private var projection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    fun start() {
        thread(name = "DirectCastSender") {
            try {
                val address = InetAddress.getByName(host)
                socket = DatagramSocket()
                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = manager.getMediaProjection(resultCode, data)
                val width = 1280
                val height = 720
                val density = context.resources.displayMetrics.densityDpi
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec!!.createInputSurface()
                codec!!.start()
                virtualDisplay = projection!!.createVirtualDisplay(
                    "DirectCast", width, height, density,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null
                )
                running = true
                report("Трансляция запущена. Отправка H.264…")
                val info = MediaCodec.BufferInfo()
                var frame = 0
                while (running) {
                    val index = codec!!.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        val buffer = codec!!.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes)
                            // MVP: one encoded access unit per UDP datagram; oversized frames are skipped.
                            if (bytes.size < 60000) {
                                val packet = DatagramPacket(bytes, bytes.size, address, 5000)
                                socket!!.send(packet)
                                frame++
                            }
                        }
                        codec!!.releaseOutputBuffer(index, false)
                    }
                }
                report("Трансляция остановлена.")
            } catch (e: Exception) {
                report("Ошибка передачи: ${e.message}")
            } finally { stop() }
        }
    }

    fun stop() {
        running = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        virtualDisplay = null; codec = null; projection = null; socket = null
    }
}

private class H264Receiver(
    private val surface: Surface,
    private val report: (String) -> Unit
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var codec: MediaCodec? = null

    fun start() {
        thread(name = "DirectCastReceiver") {
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec!!.configure(format, surface, null, 0)
                codec!!.start()
                socket = DatagramSocket(5000)
                socket!!.soTimeout = 1000
                running = true
                report("Приём H.264 активен.")
                val packetBuffer = ByteArray(65507)
                val info = MediaCodec.BufferInfo()
                while (running) {
                    try {
                        val packet = DatagramPacket(packetBuffer, packetBuffer.size)
                        socket!!.receive(packet)
                        val inputIndex = codec!!.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val input = codec!!.getInputBuffer(inputIndex)
                            if (input != null && packet.length <= input.capacity()) {
                                input.clear()
                                input.put(packet.data, packet.offset, packet.length)
                                codec!!.queueInputBuffer(inputIndex, 0, packet.length,
                                    System.nanoTime() / 1000, 0)
                            } else codec!!.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                        }
                        var outputIndex = codec!!.dequeueOutputBuffer(info, 0)
                        while (outputIndex >= 0) {
                            codec!!.releaseOutputBuffer(outputIndex, true)
                            outputIndex = codec!!.dequeueOutputBuffer(info, 0)
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            } catch (e: Exception) { report("Ошибка приёма: ${e.message}") }
            finally { stop() }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        socket = null; codec = null
    }
}
