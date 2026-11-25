package com.directcast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val reqCapture = 7101
    private val reqPermissions = 7102
    private val ui = Handler(Looper.getMainLooper())

    private var screen = "home"
    private var receiver: StreamReceiver? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var netView: TextView? = null
    private var logView: TextView? = null
    private var ipInput: EditText? = null
    private var foundBox: LinearLayout? = null
    private var overlay: View? = null
    private var surfaceView: SurfaceView? = null

    @Volatile private var discoveryToken = 0
    private var pendingCapture = false

    private val ticker = object : Runnable {
        override fun run() {
            when (screen) {
                "home" -> netView?.text = "Сеть этого устройства:\n" + NetUtil.describe()
                "sender" -> {
                    netView?.text = "Статус: ${CastService.state}\n\nСеть телефона:\n${NetUtil.describe()}"
                    logView?.text = AppLog.text(25)
                }
                "receiver" -> logView?.text = receiverText()
            }
            ui.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    override fun onResume() {
        super.onResume()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        leaveScreen()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (screen != "home") showHome() else super.onBackPressed()
    }

    // ---------- вспомогательные элементы интерфейса ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun prefs(): SharedPreferences = getSharedPreferences("directcast", MODE_PRIVATE)

    private fun label(text: String, size: Float = 15f, color: Int = 0xFFB8D8E8.toInt()): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun makeButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun newPage(title: String): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 26f
            setTextColor(Color.WHITE)
        })
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF101820.toInt())
            addView(col)
        }
        setContentView(scroll)
        return col
    }

    private fun leaveScreen() {
        discoveryToken++
        receiver?.stop()
        receiver = null
        releaseLocks()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        netView = null
        logView = null
        ipInput = null
        foundBox = null
        overlay = null
        surfaceView = null
    }

    // ---------- главный экран ----------

    private fun showHome() {
        leaveScreen()
        screen = "home"
        val col = newPage("DirectCast 0.3")
        col.addView(
            label(
                "Передача экрана телефона на проектор по Wi‑Fi — с картинкой и звуком.\n\n" +
                    "1. Включите точку доступа на телефоне и подключите к ней проектор " +
                    "(или подключите оба устройства к одной Wi‑Fi сети).\n" +
                    "2. На проекторе откройте «Приёмник».\n" +
                    "3. На телефоне откройте «Передатчик» и нажмите «Начать трансляцию».\n\n" +
                    "Приложение должно быть установлено на оба устройства, версия одна и та же.",
                16f, Color.WHITE
            )
        )
        val first = makeButton("Приёмник (на проекторе)") { showReceiver() }
        col.addView(first)
        col.addView(makeButton("Передатчик (на телефоне)") { showSender() })
        netView = label("Сеть этого устройства:\n" + NetUtil.describe())
        col.addView(netView)
        first.requestFocus()
    }

    // ---------- приёмник (проектор) ----------

    private fun showReceiver() {
        leaveScreen()
        screen = "receiver"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireLocks()

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val sv = SurfaceView(this)
        sv.isFocusable = true
        surfaceView = sv
        frame.addView(
            sv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(0xCC101820.toInt())
        }
        val tv = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            text = receiverText()
        }
        box.addView(tv)
        val back = makeButton("Назад") { showHome() }
        box.addView(back)
        frame.addView(
            box,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        )
        overlay = box
        logView = tv
        setContentView(frame)
        back.requestFocus()

        // Нажатие на картинку (или «ОК» на пульте) показывает и прячет подсказки.
        sv.setOnClickListener {
            if (box.visibility == View.VISIBLE) {
                box.visibility = View.GONE
                sv.requestFocus()
            } else {
                box.visibility = View.VISIBLE
                back.requestFocus()
            }
        }

        var mine: StreamReceiver? = null
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mine?.stop()
                val r = StreamReceiver(
                    applicationContext, holder.surface, Build.MODEL ?: "Проектор",
                    { msg -> AppLog.add(msg) },
                    { active, w, h -> runOnUiThread { onStreaming(active, w, h) } }
                )
                mine = r
                receiver = r
                r.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                mine?.stop()
                if (receiver === mine) receiver = null
                mine = null
            }
        })
    }

    private fun receiverText(): String {
        val addrs = NetUtil.localAddresses()
        val ips = if (addrs.isEmpty()) "НЕТ СЕТИ — подключите проектор к Wi‑Fi"
        else addrs.joinToString("\n") { "• ${it.ip}   (${it.ifName})" }
        return "ПРИЁМНИК ГОТОВ\n\nIP проектора:\n$ips\n\n" +
            "На телефоне: «Передатчик» → проектор найдётся сам.\n" +
            "Если нет — введите этот IP на телефоне вручную.\n\n" +
            AppLog.text(8)
    }

    private fun onStreaming(active: Boolean, w: Int, h: Int) {
        val sv = surfaceView ?: return
        val parent = sv.parent as? View ?: return
        if (active) {
            overlay?.visibility = View.GONE
            sv.requestFocus()
            val pw = parent.width
            val ph = parent.height
            if (pw > 0 && ph > 0 && w > 0 && h > 0) {
                val scale = minOf(pw.toFloat() / w, ph.toFloat() / h)
                sv.layoutParams = FrameLayout.LayoutParams((w * scale).toInt(), (h * scale).toInt(), Gravity.CENTER)
            }
        } else {
            overlay?.visibility = View.VISIBLE
            sv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Блокировки независимы: отказ одной не должен отменять другую.
        try {
            multicastLock = wm.createMulticastLock("directcast-mc").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "directcast-wifi").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try { multicastLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        multicastLock = null
        wifiLock = null
    }

    // ---------- передатчик (телефон) ----------

    private fun quality(): Int = prefs().getInt("quality", 0)

    private fun qualityLabel(): String =
        if (quality() == 0) "Качество: 720p (рекомендуется)" else "Качество: 1080p (нужен быстрый Wi‑Fi)"

    private fun audioOn(): Boolean = prefs().getBoolean("audio", true)

    private fun audioLabel(): String = if (audioOn()) "Звук: включён" else "Звук: выключен"

    private fun showSender() {
        leaveScreen()
        screen = "sender"
        val col = newPage("Передатчик")
        netView = label("")
        col.addView(netView)
        col.addView(makeButton("Найти проектор") { startDiscovery() })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        foundBox = box
        col.addView(box)
        col.addView(label("IP проектора (виден на его экране):", 14f))
        val input = EditText(this).apply {
            hint = "например 192.168.43.120"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8899AA.toInt())
            setText(prefs().getString("ip", ""))
        }
        ipInput = input
        col.addView(input)
        col.addView(makeButton("Проверить связь") { testConnection() })
        val q = makeButton(qualityLabel()) {}
        q.setOnClickListener {
            prefs().edit().putInt("quality", 1 - quality()).apply()
            q.text = qualityLabel()
        }
        col.addView(q)
        val a = makeButton(audioLabel()) {}
        a.setOnClickListener {
            prefs().edit().putBoolean("audio", !audioOn()).apply()
            a.text = audioLabel()
        }
        col.addView(a)
        col.addView(makeButton("Начать трансляцию экрана") { startCastFlow() })
        col.addView(makeButton("Остановить трансляцию") {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
        })
        col.addView(makeButton("Назад") { showHome() })
        col.addView(label("Журнал:", 14f))
        logView = label("", 13f, Color.WHITE)
        col.addView(logView)
        startDiscovery()
    }

    private fun startDiscovery() {
        val token = ++discoveryToken
        foundBox?.removeAllViews()
        if (NetUtil.localAddresses().isEmpty()) {
            AppLog.add("Нет Wi‑Fi / точки доступа: включите точку доступа и подключите к ней проектор")
            return
        }
        AppLog.add("Ищу проектор в сети…")
        val appCtx = applicationContext
        thread(name = "DC-find", isDaemon = true) {
            var count = 0
            Discovery.discover(appCtx, 3500, { discoveryToken != token }) { f ->
                count++
                runOnUiThread { if (token == discoveryToken) addFound(f) }
            }
            if (token == discoveryToken) {
                AppLog.add(
                    if (count == 0) "Проектор сам не нашёлся. На проекторе должен быть открыт «Приёмник»; можно ввести IP вручную."
                    else "Найдено проекторов: $count"
                )
            }
        }
    }

    private fun addFound(f: FoundReceiver) {
        val box = foundBox ?: return
        box.addView(makeButton("${f.name}  •  ${f.ip}") { ipInput?.setText(f.ip) })
        if (box.childCount == 1) ipInput?.setText(f.ip)
    }

    private fun currentIp(): String = ipInput?.text?.toString()?.trim().orEmpty()

    private fun testConnection() {
        val ip = currentIp()
        if (!NetUtil.validIp(ip)) {
            AppLog.add("Введите IP полностью, например 192.168.43.120")
            return
        }
        AppLog.add("Проверяю $ip:${Proto.STREAM_PORT} …")
        val appCtx = applicationContext
        thread(name = "DC-test", isDaemon = true) {
            val problem = NetUtil.probe(appCtx, ip)
            AppLog.add(if (problem == null) "ОК: DirectCast на проекторе $ip отвечает" else "Нет связи с $ip: $problem")
        }
    }

    private fun startCastFlow() {
        val ip = currentIp()
        if (!NetUtil.validIp(ip)) {
            AppLog.add("Введите IP полностью, например 192.168.43.120")
            return
        }
        if (CastService.running) {
            AppLog.add("Трансляция уже идёт. Сначала нажмите «Остановить».")
            return
        }
        prefs().edit().putString("ip", ip).apply()
        // Сначала проверяем связь, чтобы не просить разрешение на захват зря.
        AppLog.add("Проверяю связь с проектором $ip …")
        val appCtx = applicationContext
        thread(name = "DC-precheck", isDaemon = true) {
            val problem = NetUtil.probe(appCtx, ip)
            if (problem == null) runOnUiThread { askPermissionsAndCapture() }
            else AppLog.add("Нет связи с $ip: $problem")
        }
    }

    private fun askPermissionsAndCapture() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 29 && audioOn() &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.RECORD_AUDIO)
        if (need.isNotEmpty()) {
            pendingCapture = true
            requestPermissions(need.toTypedArray(), reqPermissions)
            return
        }
        launchCapture()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqPermissions && pendingCapture) {
            pendingCapture = false
            launchCapture()   // без уведомления и без звука трансляция всё равно работает
        }
    }

    private fun launchCapture() {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            AppLog.add("Подтвердите захват экрана в системном окне Android")
            startActivityForResult(mgr.createScreenCaptureIntent(), reqCapture)
        } catch (e: Exception) {
            AppLog.add("Не удалось запросить захват экрана: ${e.message}")
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode == RESULT_OK && data != null) {
            val ip = prefs().getString("ip", "") ?: ""
            val hd = quality() == 1
            val i = Intent(this, CastService::class.java)
                .setAction(CastService.ACTION_START)
                .putExtra(CastService.EXTRA_CODE, resultCode)
                .putExtra(CastService.EXTRA_DATA, data)
                .putExtra(CastService.EXTRA_HOST, ip)
                .putExtra(CastService.EXTRA_PORT, Proto.STREAM_PORT)
                .putExtra(CastService.EXTRA_W, if (hd) 1920 else 1280)
                .putExtra(CastService.EXTRA_H, if (hd) 1080 else 720)
                .putExtra(CastService.EXTRA_BITRATE, if (hd) 8_000_000 else 4_000_000)
                .putExtra(CastService.EXTRA_AUDIO, audioOn())
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            AppLog.add("Захват экрана не разрешён")
        }
    }
}
