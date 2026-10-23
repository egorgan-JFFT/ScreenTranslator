package com.example.screentranslator

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import kotlin.math.*
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONObject

class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var bubble: View? = null
    private var resultOverlay: View? = null
    private var selectingOverlay: View? = null
    private var busy = false
    private var anchor: android.graphics.PointF? = null
    private var selectionRect: Rect? = null
    private var holdRunnable: Runnable? = null
    private var selectionHint: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val languageId = LanguageIdentification.getClient()
    private val recognizers: List<TextRecognizer> by lazy { listOf(
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    ) }
    private val translators = mutableMapOf<String, Translator>()
    private data class Segment(val rect: Rect, val text: String)
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("capture", "Перевод экрана", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val note = Notification.Builder(this, "capture").setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Screen Translator").setContentText("Переводчик экрана активен").build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, note)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
                   else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)
        val m = resources.displayMetrics
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("TranslateCapture", m.widthPixels, m.heightPixels,
            m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        showBubble()
        return START_NOT_STICKY
    }
    private fun params(w: Int, h: Int, gravity: Int = Gravity.TOP or Gravity.START) =
        WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply { this.gravity = gravity }
    private fun showBubble() {
        val button = Button(this).apply {
            text = "TR"
            setOnClickListener {
                if (busy) return@setOnClickListener
                if (resultOverlay != null) dismissResult() else capture(null)
            }
        }
        val side = (52f * resources.displayMetrics.density).toInt()
        val p = params(side, side).apply { x = 0; y = 220 }
        val threshold = 12f * resources.displayMetrics.density
        val holdMs = 2000L
        button.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var oldX = 0
            var oldY = 0
            var moved = false
            var lastX = 0f
            var lastY = 0f
            fun cancelHold() {
                holdRunnable?.let { handler.removeCallbacks(it) }
                holdRunnable = null
            }
            fun armHold() {
                cancelHold()
                val task = Runnable {
                    if (!busy && anchor == null && resultOverlay == null) {
                        // Anchor at the bubble's top-left corner, not the finger.
                        val x = p.x.toFloat()
                        val y = p.y.toFloat()
                        anchor = android.graphics.PointF(x, y)
                        selectionRect = Rect(x.toInt(), y.toInt(), x.toInt(), y.toInt())
                        showSelectionHint()
                    }
                }
                holdRunnable = task
                handler.postDelayed(task, holdMs)
            }
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        lastX = e.rawX; lastY = e.rawY
                        oldX = p.x; oldY = p.y; moved = false
                        anchor = null
                        armHold()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val x = e.rawX; val y = e.rawY
                        if (abs(x-lastX) > threshold || abs(y-lastY) > threshold) {
                            lastX = x; lastY = y
                            if (anchor == null) armHold()
                        }
                        if (abs(x-downX)>threshold || abs(y-downY)>threshold) moved = true
                        if (moved) {
                            p.x = oldX+(x-downX).toInt()
                            p.y = oldY+(y-downY).toInt()
                            wm.updateViewLayout(button,p)
                        }
                        anchor?.let { start ->
                            val endX = p.x.toFloat()
                            val endY = p.y.toFloat()
                            selectionRect = Rect(min(start.x,endX).toInt(), min(start.y,endY).toInt(),
                                max(start.x,endX).toInt(), max(start.y,endY).toInt())
                            selectionHint?.invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelHold()
                        val rect = selectionRect
                        val wasSelecting = anchor != null
                        anchor = null
                        removeSelectionHint()
                        selectionRect = null
                        if (wasSelecting) {
                            if (rect != null && rect.width()>30 && rect.height()>30) {
                                dismissResult()
                                capture(rect)
                            }
                        } else if (!moved) {
                            v.performClick()
                        } else if (e.rawY-downY < -65f*resources.displayMetrics.density && resultOverlay != null) {
                            dismissResult()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelHold(); anchor = null; selectionRect = null; removeSelectionHint()
                        return true
                    }
                }
                return false
            }
        })
        bubble = button
        wm.addView(button,p)
    }
    private fun dismissResult() {
        resultOverlay?.let { try { wm.removeView(it) } catch (_: IllegalArgumentException) {} }
        resultOverlay = null
    }
    private fun removeSelectionHint() {
        selectionHint?.let { try { wm.removeView(it) } catch (_: IllegalArgumentException) {} }
        selectionHint = null
    }
    private fun showSelectionHint() {
        val density = resources.displayMetrics.density
        val overlay = object : View(this) {
            private val outline = Paint(3).apply {
                color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f*density
            }
            private val marker = Paint(3).apply { color = Color.YELLOW; textSize = 20f*density }
            override fun onDraw(canvas: Canvas) {
                val start = anchor ?: return
                canvas.drawText("➤",start.x,start.y + 14f*density,marker)
                selectionRect?.let { canvas.drawRect(it,outline) }
            }
        }
        selectionHint = overlay
        val m = resources.displayMetrics
        wm.addView(overlay,params(m.widthPixels,m.heightPixels).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        })
    }
    private fun capture(region: Rect?) {
        if (busy) return
        busy = true
        bubble?.visibility = View.INVISIBLE
        handler.postDelayed({
            val image = reader?.acquireLatestImage()
            if (image == null) { fail("Не удалось получить кадр"); return@postDelayed }
            var bitmap: Bitmap? = null
            try {
                val plane = image.planes[0]
                val width = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
                val padded = Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                padded.recycle()
            } catch (e: Exception) { fail("Ошибка кадра: ${e.message}") }
            finally { image.close() }
            val shot = bitmap ?: return@postDelayed
            recognizeAll(shot, region)
        }, 550)
    }
    private fun recognizeAll(bitmap: Bitmap, region: Rect?) {
        val segments = mutableListOf<Segment>()
        val input = InputImage.fromBitmap(bitmap, 0)
        fun next(index: Int) {
            if (index == recognizers.size) {
                bitmap.recycle()
                val unique = segments.distinctBy { "${it.rect.left/12}:${it.rect.top/12}:${it.text.trim()}" }
                if (unique.isEmpty()) { fail("Текст не найден"); return }
                translateAll(unique)
                return
            }
            recognizers[index].process(input).addOnSuccessListener { result ->
                for (line in result.textBlocks.flatMap { it.lines }) {
                    val rect = line.boundingBox ?: continue
                    if (line.text.isNotBlank() && (region == null || Rect.intersects(rect, region))) {
                        // Keep first recognized text for overlapping scripts.
                        if (segments.none { existing -> overlap(existing.rect,rect) > 0.65f })
                            segments.add(Segment(Rect(rect), line.text))
                    }
                }
            }.addOnCompleteListener { next(index+1) }
        }
        next(0)
    }
    private fun overlap(a: Rect,b: Rect): Float {
        val i=Rect(a)
        if(!i.intersect(b)) return 0f
        return i.width().toFloat()*i.height() / max(1f,min(a.width().toFloat()*a.height(),b.width().toFloat()*b.height()))
    }
    private val sourceCodes = arrayOf("auto", "zh", "en", "ja", "ko", "ru")
    private val targetCodes = arrayOf("ru", "zh", "en", "ja", "ko")

    private fun translateAll(items: List<Segment>) {
        val output = mutableListOf<Pair<Rect,String>>()
        val provider = prefs.getInt("provider",0)
        val configuredSource = sourceCodes[prefs.getInt("source",0).coerceIn(sourceCodes.indices)]
        val target = targetCodes[prefs.getInt("target",0).coerceIn(targetCodes.indices)]
        fun next(i: Int) {
            if (i >= items.size) {
                busy = false
                if (output.isEmpty()) fail("Перевод не получен. Проверьте язык, API-ключ и подключение.")
                else showResult(output)
                return
            }
            val item = items[i]
            fun translate(source: String) {
                if (source == target) { next(i+1); return }
                if (provider == 0) {
                    val key = "$source:$target"
                    val translator = try {
                        translators.getOrPut(key) {
                            Translation.getClient(TranslatorOptions.Builder()
                                .setSourceLanguage(source).setTargetLanguage(target).build())
                        }
                    } catch (e: Exception) { next(i+1); return }
                    translator.downloadModelIfNeeded().addOnSuccessListener {
                        translator.translate(item.text).addOnSuccessListener { result ->
                            output.add(item.rect to result)
                            next(i+1)
                        }.addOnFailureListener { next(i+1) }
                    }.addOnFailureListener { next(i+1) }
                } else {
                    Thread {
                        val result = try {
                            if (provider == 1) googleTranslate(item.text, source, target)
                            else baiduTranslate(item.text, source, target)
                        } catch (e: Exception) {
                            handler.post { Toast.makeText(this, "Ошибка API: ${e.message}", Toast.LENGTH_LONG).show() }
                            null
                        }
                        handler.post {
                            if (result != null) output.add(item.rect to result)
                            next(i+1)
                        }
                    }.start()
                }
            }
            if (configuredSource != "auto") translate(configuredSource)
            else languageId.identifyLanguage(item.text).addOnSuccessListener { code ->
                val source = when {
                    code.startsWith("zh") -> "zh"
                    code in arrayOf("en","ja","ko","ru") -> code
                    item.text.any { it.code in 0x4E00..0x9FFF } -> "zh"
                    else -> "en"
                }
                translate(source)
            }.addOnFailureListener { translate("zh") }
        }
        next(0)
    }

    private fun postForm(endpoint: String, params: Map<String,String>): JSONObject {
        val payload = params.entries.joinToString("&") {
            URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")
        }
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}: ${body.take(120)}")
            return JSONObject(body)
        } finally { conn.disconnect() }
    }

    private fun googleTranslate(text: String, source: String, target: String): String {
        val key = prefs.getString("google_key","").orEmpty()
        if (key.isBlank()) throw Exception("Не задан Google Cloud API key")
        val response = postForm("https://translation.googleapis.com/language/translate/v2?key="+
            URLEncoder.encode(key,"UTF-8"),
            buildMap {
                put("q",text); put("target",target); put("format","text")
                if (source != "auto") put("source",source)
            })
        return android.text.Html.fromHtml(
            response.getJSONObject("data").getJSONArray("translations")
                .getJSONObject(0).getString("translatedText"), android.text.Html.FROM_HTML_MODE_LEGACY
        ).toString()
    }

    private fun baiduTranslate(text: String, source: String, target: String): String {
        val appid = prefs.getString("baidu_appid","").orEmpty()
        val secret = prefs.getString("baidu_secret","").orEmpty()
        if (appid.isBlank() || secret.isBlank()) throw Exception("Не заданы Baidu App ID / Secret")
        val salt = System.currentTimeMillis().toString()
        val raw = appid+text+salt+secret
        val sign = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val result = postForm("https://fanyi-api.baidu.com/api/trans/vip/translate",
            mapOf("q" to text,"from" to source,"to" to target,
                "appid" to appid,"salt" to salt,"sign" to sign))
        if (result.has("error_code")) throw Exception("Baidu ${result.optString("error_code")}: ${result.optString("error_msg")}")
        return result.getJSONArray("trans_result").getJSONObject(0).getString("dst")
    }
    private fun fail(message: String) { busy=false;bubble?.visibility=View.VISIBLE;Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun showResult(items: List<Pair<Rect,String>>) {
        bubble?.visibility=View.VISIBLE
        if (items.isEmpty()) { Toast.makeText(this,"Перевод не получен. Проверьте интернет и модели.",Toast.LENGTH_LONG).show();return }
        if (prefs.getInt("mode",0)==0) {
            val size = prefs.getInt("size",14).toFloat()*resources.displayMetrics.scaledDensity
            val dark = prefs.getInt("theme",0)==1
            val view=object : View(this) {
                val textPaint=Paint(3).apply { color=if(dark) Color.WHITE else Color.BLACK; textSize=size }
                val bg=Paint().apply { color=if(dark) Color.BLACK else Color.WHITE }
                override fun onDraw(canvas: Canvas) {
                    for((rect, text) in items) {
                        val maxWidth = max(rect.width().toFloat(),size*5f).coerceAtMost(width-rect.left.toFloat()-8f)
                        if(maxWidth<=0f) continue
                        val words=text.split(" ")
                        val lines=mutableListOf<String>();var line=""
                        for(word in words) {
                            val candidate=if(line.isEmpty()) word else "$line $word"
                            if(line.isNotEmpty() && textPaint.measureText(candidate)>maxWidth) { lines.add(line);line=word }
                            else line=candidate
                        }
                        if(line.isNotEmpty()) lines.add(line)
                        val lineHeight=size*1.22f
                        val bottom=(rect.top+lines.size*lineHeight+6f).coerceAtMost(height.toFloat())
                        canvas.drawRect(rect.left.toFloat(),rect.top.toFloat(),(rect.left+maxWidth+8f).coerceAtMost(width.toFloat()),bottom,bg)
                        for((index,s) in lines.withIndex()) canvas.drawText(s,rect.left+4f,rect.top+size+index*lineHeight,textPaint)
                    }
                }
            }
            // Swipe UP on the translation layer to dismiss it (as in v0.5).
            view.setOnTouchListener(object : View.OnTouchListener {
                var startY = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { startY = event.rawY; return true }
                        MotionEvent.ACTION_UP -> {
                            if (startY-event.rawY > 45f*resources.displayMetrics.density) dismissResult()
                            return true
                        }
                    }
                    return true
                }
            })
            resultOverlay=view
            wm.addView(view,params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT))
        } else {
            val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,12,16,12);setBackgroundColor(Color.WHITE) }
            layout.addView(Button(this).apply { text="Закрыть ✕";setOnClickListener { dismissResult() } })
            val scroll=ScrollView(this)
            scroll.addView(TextView(this).apply { text=items.joinToString("\n\n") { it.second };setTextColor(Color.BLACK);textSize=prefs.getInt("size",14).toFloat() })
            layout.addView(scroll)
            resultOverlay=layout
            wm.addView(layout,params(resources.displayMetrics.widthPixels*9/10,resources.displayMetrics.heightPixels/2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
    }
    override fun onDestroy() {
        holdRunnable?.let { handler.removeCallbacks(it) }
        removeSelectionHint()
        selectingOverlay?.let { wm.removeView(it) };dismissResult();bubble?.let { wm.removeView(it) }
        display?.release();reader?.close();projection?.stop();recognizers.forEach { it.close() }
        languageId.close();translators.values.forEach { it.close() }
        super.onDestroy()
    }
}
