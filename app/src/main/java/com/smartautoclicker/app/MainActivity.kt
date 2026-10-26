package com.smartautoclicker.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var intervalSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var latency: TextView
    private lateinit var results: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var testTask: ScheduledFuture<*>? = null
    private var testCount = 0
    private val prefs by lazy { getSharedPreferences("wifi_test_settings", MODE_PRIVATE) }
    private val intervals = listOf(1, 5, 10, 15, 20, 25, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 17, 23)
        window.navigationBarColor = Color.rgb(13, 17, 23)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(13, 17, 23))
        }

        fun label(text: String, size: Float, bold: Boolean = false): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 10, 0, 10)
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
            }
        }

        fun button(text: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 6 })
        }

        root.addView(label("اختبار الواي فاي", 26f, true))
        root.addView(label("اختبر اتصال الإنترنت وسجّل زمن الاستجابة", 15f))

        root.addView(label("الفاصل الزمني بين الاختبارات", 17f, true))
        intervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                intervals.map { "$it ثانية" }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(intervals.indexOf(prefs.getInt("interval", 5)).coerceAtLeast(0))
        }
        root.addView(intervalSpinner, LinearLayout.LayoutParams(-1, -2))

        connection = label("الشبكة الحالية: جارٍ التحقق...", 16f)
        status = label("الحالة: متوقف", 16f, true)
        latency = label("آخر نتيجة: —", 18f, true)
        results = label("عدد الاختبارات: 0", 14f)
        root.addView(connection)
        root.addView(status)
        root.addView(latency)
        root.addView(results)

        button("بدء الاختبار") { startTesting() }
        button("إيقاف الاختبار") { stopTesting() }
        button("تحديث حالة الشبكة") { updateConnectionLabel() }
        root.addView(label("يقيس التطبيق الوصول إلى خدمة اختبار اتصال عامة. لا يجرّب كلمات مرور ولا يتصل بشبكات غير محفوظة.", 12f))

        setContentView(ScrollView(this).apply { addView(root) })
        updateConnectionLabel()
    }

    private fun selectedInterval(): Int {
        val selected = intervals.getOrElse(intervalSpinner.selectedItemPosition) { 5 }
        prefs.edit().putInt("interval", selected).apply()
        return selected
    }

    private fun startTesting() {
        stopTaskOnly()
        val seconds = selectedInterval()
        testCount = 0
        results.text = "عدد الاختبارات: 0"
        status.text = "الحالة: الاختبار يعمل كل $seconds ثانية"
        testTask = executor.scheduleAtFixedRate({ runConnectivityTest() }, 0, seconds.toLong(), TimeUnit.SECONDS)
    }

    private fun stopTesting() {
        stopTaskOnly()
        status.text = "الحالة: متوقف"
    }

    private fun stopTaskOnly() {
        testTask?.cancel(true)
        testTask = null
    }

    private fun runConnectivityTest() {
        val start = System.nanoTime()
        var resultText: String
        var success = false
        var code: Int? = null
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://connectivitycheck.gstatic.com/generate_204").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = false
            }
            code = connection.responseCode
            val elapsed = (System.nanoTime() - start) / 1_000_000
            success = code == 204 || (code != null && code in 200..299)
            resultText = if (success) "نجح الاتصال — زمن الاستجابة: ${elapsed} ms" else "استجابة غير متوقعة: HTTP $code"
        } catch (e: Exception) {
            resultText = "فشل الوصول للإنترنت (${e.javaClass.simpleName})"
        } finally {
            connection?.disconnect()
        }
        handler.post {
            testCount++
            latency.text = "آخر نتيجة: $resultText"
            results.text = "عدد الاختبارات: $testCount"
            updateConnectionLabel()
        }
    }

    private fun updateConnectionLabel() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        connection.text = when {
            caps == null -> "الشبكة الحالية: لا يوجد اتصال نشط"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "الشبكة الحالية: Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "الشبكة الحالية: بيانات الهاتف"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "الشبكة الحالية: Ethernet"
            else -> "الشبكة الحالية: اتصال آخر"
        }
    }

    override fun onDestroy() {
        stopTaskOnly()
        executor.shutdownNow()
        super.onDestroy()
    }
}