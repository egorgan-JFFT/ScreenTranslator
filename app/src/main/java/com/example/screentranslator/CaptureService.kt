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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors
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
    private var regionMode = false
    private var pendingTap: Runnable? = null
    private var lastTap = 0L
    private val network = Executors.newSingleThreadExecutor()
    private val languageCodes = arrayOf("ru", "zh", "en", "ja", "ko")
    private fun selectedTarget(): String = languageCodes[prefs.getInt("target",0).coerceIn(0,4)]
    private fun selectedSource(): String? = when (prefs.getInt("source",0)) {
        1 -> "zh"; 2 -> "en"; 3 -> "ja"; 4 -> "ko"; 5 -> "ru"; else -> null
    }
    private fun normalized(code: String): String = when {
        code.startsWith("zh") -> "zh"
        code.startsWith("ja") -> "ja"
        code.startsWith("ko") -> "ko"
        code.startsWith("ru") -> "ru"
        code.startsWith("en") -> "en"
        else -> if (code == "und") "zh" else code
    }
    private fun onBubbleTap() {
        if (busy) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTap < 330L) {
            pendingTap?.let { handler.removeCallbacks(it) }
            pendingTap = null
            lastTap = 0L
            regionMode = !regionMode
            Toast.makeText(this, if (regionMode) "Режим: выделить область" else "Режим: весь экран", Toast.LENGTH_SHORT).show()
        } else {
            lastTap = now
            val action = Runnable {
                pendingTap = null
                if (!busy) { dismissResult(); if (regionMode) selectRegion() else capture(null) }
            }
            pendingTap = action
            handler.postDelayed(action, 340L)
        }
    }
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
        val button = Button(this).apply { text = "TR"; setOnClickListener { onBubbleTap() } }
        val p = params(145, 115).apply { x = 0; y = 220 }
        button.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var oldX = 0; var oldY = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; oldX = p.x; oldY = p.y; moved = false; return true }
                    MotionEvent.ACTION_MOVE -> { if (abs(e.rawX-downX)>10 || abs(e.rawY-downY)>10) moved = true
                        if (moved) { p.x = oldX+(e.rawX-downX).toInt(); p.y = oldY+(e.rawY-downY).toInt(); wm.updateViewLayout(button,p) }; return true }
                    MotionEvent.ACTION_UP -> { if (!moved) v.performClick(); return true }
                }
                return false
            }
        })
        bubble = button; wm.addView(button, p)
    }
    private fun dismissResult() { resultOverlay?.let { wm.removeView(it) }; resultOverlay = null }
    private fun selectRegion() {
        bubble?.visibility = View.INVISIBLE
        val m = resources.displayMetrics
        val view = object : View(this) {
            var startX = 0f; var startY = 0f; var endX = 0f; var endY = 0f
            val shade = Paint().apply { color = Color.argb(90,0,0,0) }
            val outline = Paint(3).apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f }
            val textPaint = Paint(3).apply { color = Color.WHITE; textSize = 36f }
            override fun onDraw(c: Canvas) {
                c.drawColor(Color.TRANSPARENT)
                c.drawText("Выделите область пальцем", 24f, 65f, textPaint)
                if (abs(endX-startX)>3 && abs(endY-startY)>3) {
                    c.drawRect(min(startX,endX),min(startY,endY),max(startX,endX),max(startY,endY),shade)
                    c.drawRect(min(startX,endX),min(startY,endY),max(startX,endX),max(startY,endY),outline)
                }
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when(e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { startX=e.x;startY=e.y;endX=e.x;endY=e.y;invalidate();return true }
                    MotionEvent.ACTION_MOVE -> { endX=e.x;endY=e.y;invalidate();return true }
                    MotionEvent.ACTION_UP -> { endX=e.x;endY=e.y
                        val rect=Rect(min(startX,endX).toInt(),min(startY,endY).toInt(),max(startX,endX).toInt(),max(startY,endY).toInt())
                        selectingOverlay?.let { wm.removeView(it) };selectingOverlay=null
                        if(rect.width()>24 && rect.height()>24) capture(rect) else bubble?.visibility=View.VISIBLE
                        return true }
                }
                return true
            }
        }
        selectingOverlay = view
        wm.addView(view, params(m.widthPixels, m.heightPixels))
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
    private fun translateAll(items: List<Segment>) {
        val translated = mutableListOf<Pair<Rect,String>>()
        val target = selectedTarget()
        val provider = prefs.getInt("provider",0)
        fun next(i: Int) {
            if (i >= items.size) { busy=false; showResult(translated); return }
            val item = items[i]
            fun translate(source: String) {
                if (source == target) { next(i+1); return }
                if (provider == 0) {
                    val client = translators.getOrPut("$source:$target") {
                        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source)
                            .setTargetLanguage(target).build())
                    }
                    client.downloadModelIfNeeded().addOnSuccessListener {
                        client.translate(item.text).addOnSuccessListener { result ->
                            translated.add(item.rect to result); next(i+1)
                        }.addOnFailureListener { next(i+1) }
                    }.addOnFailureListener { e ->
                        Toast.makeText(this,"Модель: ${e.localizedMessage}",Toast.LENGTH_LONG).show()
                        next(i+1)
                    }
                } else {
                    network.execute {
                        val result = try { onlineTranslate(provider, item.text, source, target) }
                                     catch (e: Exception) { android.util.Log.e("ScreenTranslator", "Online translation failed", e); null }
                        handler.post {
                            if (result != null) translated.add(item.rect to result)
                            else Toast.makeText(this, "Ошибка онлайн-перевода. Проверьте ключ API и сеть", Toast.LENGTH_LONG).show()
                            next(i+1)
                        }
                    }
                }
            }
            val forced = selectedSource()
            if (forced != null) translate(forced)
            else languageId.identifyLanguage(item.text).addOnSuccessListener { detected ->
                val language = normalized(detected)
                translate(if (language in listOf("zh","en","ja","ko","ru")) language else "zh")
            }.addOnFailureListener { translate("zh") }
        }
        next(0)
    }
    private fun onlineTranslate(provider: Int, text: String, source: String, target: String): String? {
        fun encode(s: String) = URLEncoder.encode(s,"UTF-8")
        val url: String
        val payload: String
        if (provider == 1) {
            val key = prefs.getString("google_key", "").orEmpty()
            if (key.isBlank()) return null
            url = "https://translation.googleapis.com/language/translate/v2?key=${encode(key)}"
            payload = "q=${encode(text)}&source=${encode(source)}&target=${encode(target)}&format=text"
        } else {
            val appId = prefs.getString("baidu_appid", "").orEmpty()
            val secret = prefs.getString("baidu_secret", "").orEmpty()
            if (appId.isBlank() || secret.isBlank()) return null
            val salt = System.currentTimeMillis().toString()
            fun baiduCode(lang: String) = when(lang) { "ja" -> "jp"; "ko" -> "kor"; else -> lang }
            val bytes = MessageDigest.getInstance("MD5").digest("$appId$text$salt$secret".toByteArray(Charsets.UTF_8))
            val sign = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            url = "https://fanyi-api.baidu.com/api/trans/vip/translate"
            payload = "q=${encode(text)}&from=${encode(baiduCode(source))}&to=${encode(baiduCode(target))}&appid=${encode(appId)}&salt=$salt&sign=$sign"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 18000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            return if (provider == 1) {
                json.getJSONObject("data").getJSONArray("translations").getJSONObject(0).getString("translatedText")
                    .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            } else json.getJSONArray("trans_result").getJSONObject(0).getString("dst")
        } finally { conn.disconnect() }
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
            view.setOnClickListener { dismissResult() }
            resultOverlay=view
            wm.addView(view,params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE })
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
        selectingOverlay?.let { wm.removeView(it) };dismissResult();bubble?.let { wm.removeView(it) }
        display?.release();reader?.close();projection?.stop();recognizers.forEach { it.close() }
        pendingTap?.let { handler.removeCallbacks(it) }
        network.shutdownNow()
        languageId.close();translators.values.forEach { it.close() }
        super.onDestroy()
    }
}
