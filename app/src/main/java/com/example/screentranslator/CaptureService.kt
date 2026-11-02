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

/**
 * v0.12: кнопка «RU→ZH» при вводе текста — переводит введённое на язык оригинала и вставляет в поле.
 * v0.11: перевод стоит на месте оригинала (вписывается в его рамку, шрифт уменьшается),
 *   соседние блоки разделены цветом, тап по блоку — полный текст.
 * v0.10
 * - Все окна поверх экрана используют экранные координаты (с учётом строки состояния и выреза),
 *   поэтому рамка, распознанный текст и перевод совпадают по положению.
 * - Выделение области: рамка = прямоугольник, охватывающий кнопку TR в момент удержания
 *   и кнопку TR сейчас. Кнопка всегда остаётся на экране, а рамка достаёт до любого края.
 * - Распознавание одним движком (по выбранному языку), без повторных проходов.
 * - Блоки перевода больше не пропускаются: сначала уменьшается шрифт, затем включается прокрутка.
 */
class CaptureService : Service() {
    companion object {
        @Volatile var instance: CaptureService? = null
            private set
    }
    private var inputButton: Button? = null
    private var inputParams: WindowManager.LayoutParams? = null
    private var inputActive = false
    private var inputBusy = false
    private var fallbackWindow: View? = null
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var resultOverlay: View? = null
    private var detailPanel: View? = null
    private var busy = false
    private var holdRect: Rect? = null          // кнопка TR в момент начала выделения (экранные координаты)
    private var selectionRect: Rect? = null     // текущая рамка (экранные координаты)
    private var holdRunnable: Runnable? = null
    private var selectionHint: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val languageId = LanguageIdentification.getClient()
    private var recognizer: TextRecognizer? = null
    private var recognizerKind = -1
    private val translators = mutableMapOf<String, Translator>()
    private data class Segment(val rect: Rect, val text: String)
    private class Shown(val rect: Rect, val original: String, val translated: String)
    private class Placed(val box: RectF, val lines: List<String>, val size: Float, val truncated: Boolean,
                         val item: Shown, var color: Int = 0)

    private val sourceCodes = arrayOf("auto", "zh", "en", "ja", "ko", "ru")
    private val targetCodes = arrayOf("ru", "zh", "en", "ja", "ko")

    override fun onBind(intent: Intent?) = null

    private fun screenSize(): Point {
        val p = Point()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealSize(p)
        return p
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("capture", "Перевод экрана", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val note = Notification.Builder(this, "capture").setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Screen Translator").setContentText("Переводчик экрана активен").build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, note)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (projection != null) return START_NOT_STICKY   // уже запущен
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
                   else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { handler.post { stopSelf() } }
        }, handler)
        val size = screenSize()
        reader = ImageReader.newInstance(size.x, size.y, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay("TranslateCapture", size.x, size.y,
            resources.displayMetrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
        showBubble()
        instance = this
        // Если служба ввода уже включена — сразу узнать, открыто ли поле ввода.
        handler.postDelayed({ InputAssistService.instance?.let { setInputActive(it.readFocusedText() != null) } }, 300)
        return START_NOT_STICKY
    }

    private fun params(w: Int, h: Int, gravity: Int = Gravity.TOP or Gravity.START) =
        WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    private fun fullScreenParams(): WindowManager.LayoutParams {
        val s = screenSize()
        return params(s.x, s.y).apply { x = 0; y = 0 }
    }

    private fun showBubble() {
        val button = Button(this).apply {
            text = "TR"
            setOnClickListener {
                if (busy) return@setOnClickListener
                if (resultOverlay != null) dismissResult() else capture(null)
            }
        }
        val side = dp(52f).toInt()
        val p = params(side, side).apply { x = 0; y = 220 }
        val threshold = dp(12f)
        val holdMs = 1000L
        button.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var oldX = 0
            var oldY = 0
            var offX = 0      // разница между экранными координатами и координатами окна
            var offY = 0
            var moved = false
            var lastX = 0f
            var lastY = 0f
            fun bubbleScreenRect() = Rect(p.x + offX, p.y + offY, p.x + offX + side, p.y + offY + side)
            fun cancelHold() {
                holdRunnable?.let { handler.removeCallbacks(it) }
                holdRunnable = null
            }
            fun armHold() {
                cancelHold()
                val task = Runnable {
                    if (!busy && holdRect == null && resultOverlay == null) {
                        val r = bubbleScreenRect()
                        holdRect = Rect(r)
                        selectionRect = Rect(r)
                        button.alpha = 0.45f
                        showSelectionHint()
                        updateInputButton()
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
                        val loc = IntArray(2); v.getLocationOnScreen(loc)
                        offX = loc[0] - p.x; offY = loc[1] - p.y
                        holdRect = null
                        armHold()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val x = e.rawX; val y = e.rawY
                        if (abs(x - lastX) > threshold || abs(y - lastY) > threshold) {
                            lastX = x; lastY = y
                            if (holdRect == null) armHold()
                        }
                        if (abs(x - downX) > threshold || abs(y - downY) > threshold) moved = true
                        if (moved) {
                            val s = screenSize()
                            // Кнопка всегда целиком на экране.
                            p.x = (oldX + (x - downX).toInt()).coerceIn(-offX, s.x - side - offX)
                            p.y = (oldY + (y - downY).toInt()).coerceIn(-offY, s.y - side - offY)
                            wm.updateViewLayout(button, p)
                            positionInputButton()
                        }
                        holdRect?.let { start ->
                            // Рамка охватывает кнопку в момент удержания и кнопку сейчас:
                            // так край рамки доходит до края экрана, а кнопка не уходит за него.
                            val r = Rect(start); r.union(bubbleScreenRect())
                            selectionRect = r
                            selectionHint?.invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelHold()
                        val rect = selectionRect
                        val wasSelecting = holdRect != null
                        holdRect = null
                        selectionRect = null
                        removeSelectionHint()
                        button.alpha = 1f
                        updateInputButton()
                        if (wasSelecting) {
                            if (rect != null && rect.width() > side + 10 || rect != null && rect.height() > side + 10) {
                                dismissResult()
                                capture(rect)
                            }
                        } else if (!moved) {
                            v.performClick()
                        } else if (e.rawY - downY < -dp(65f) && resultOverlay != null) {
                            dismissResult()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelHold(); holdRect = null; selectionRect = null; removeSelectionHint()
                        button.alpha = 1f
                        return true
                    }
                }
                return false
            }
        })
        bubble = button
        bubbleParams = p
        wm.addView(button, p)
    }

    /** Поднять кнопку TR над окном перевода, чтобы её можно было нажать для закрытия. */
    private fun bringBubbleToFront() {
        val b = bubble ?: return
        val p = bubbleParams ?: return
        try { wm.removeView(b); wm.addView(b, p) } catch (_: Exception) {}
        val ib = inputButton ?: return
        val ip = inputParams ?: return
        try { wm.removeView(ib); wm.addView(ib, ip) } catch (_: Exception) {}
    }

    private fun dismissResult() {
        dismissDetail()
        resultOverlay?.let { try { wm.removeView(it) } catch (_: IllegalArgumentException) {} }
        resultOverlay = null
    }

    private fun removeSelectionHint() {
        selectionHint?.let { try { wm.removeView(it) } catch (_: IllegalArgumentException) {} }
        selectionHint = null
    }

    private fun showSelectionHint() {
        val overlay = object : View(this) {
            private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = dp(2f)
            }
            private val fill = Paint().apply { color = Color.argb(40, 255, 255, 0) }
            private val ghost = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = dp(1f)
                pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
            }
            private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED; style = Paint.Style.STROKE; strokeWidth = dp(4f)
            }
            override fun onDraw(canvas: Canvas) {
                val loc = IntArray(2); getLocationOnScreen(loc)
                canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
                holdRect?.let { canvas.drawRect(it, ghost) }
                val r = selectionRect ?: return
                canvas.drawRect(r, fill)
                canvas.drawRect(r, outline)
                // Красные уголки, чтобы точно видеть границы рамки.
                val c = dp(14f)
                val l = r.left.toFloat(); val t = r.top.toFloat(); val rr = r.right.toFloat(); val b = r.bottom.toFloat()
                canvas.drawLine(l, t, l + c, t, corner); canvas.drawLine(l, t, l, t + c, corner)
                canvas.drawLine(rr, t, rr - c, t, corner); canvas.drawLine(rr, t, rr, t + c, corner)
                canvas.drawLine(l, b, l + c, b, corner); canvas.drawLine(l, b, l, b - c, corner)
                canvas.drawLine(rr, b, rr - c, b, corner); canvas.drawLine(rr, b, rr, b - c, corner)
            }
        }
        selectionHint = overlay
        wm.addView(overlay, fullScreenParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        })
    }

    private fun capture(region: Rect?) {
        if (busy) return
        busy = true
        bubble?.visibility = View.INVISIBLE
        updateInputButton()
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
            // Для выделенной области распознаём только её — быстрее и без лишнего текста.
            if (region != null) {
                val r = Rect(region)
                if (!r.intersect(0, 0, shot.width, shot.height) || r.width() < 8 || r.height() < 8) {
                    shot.recycle(); fail("Область слишком мала"); return@postDelayed
                }
                val crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
                if (crop != shot) shot.recycle()
                recognize(crop, r.left, r.top)
            } else recognize(shot, 0, 0)
        }, 550)
    }

    /** Один движок распознавания по выбранному языку оригинала. Китайский движок читает и латиницу. */
    private fun recognizerFor(source: String): TextRecognizer {
        val kind = when (source) { "ja" -> 2; "ko" -> 3; "en", "ru" -> 1; else -> 0 }
        recognizer?.let { if (recognizerKind == kind) return it; it.close() }
        val r = when (kind) {
            1 -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            2 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            3 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        }
        recognizer = r; recognizerKind = kind
        return r
    }

    private fun recognize(bitmap: Bitmap, dx: Int, dy: Int) {
        val source = sourceCodes[prefs.getInt("source", 0).coerceIn(sourceCodes.indices)]
        recognizerFor(source).process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val segments = mutableListOf<Segment>()
                for (line in result.textBlocks.flatMap { it.lines }) {
                    val box = line.boundingBox ?: continue
                    if (line.text.isBlank()) continue
                    val rect = Rect(box); rect.offset(dx, dy)
                    segments.add(Segment(rect, line.text))
                }
                if (segments.isEmpty()) fail("Текст не найден") else translateAll(segments)
            }
            .addOnFailureListener { fail("Ошибка распознавания: ${it.message}") }
            .addOnCompleteListener { bitmap.recycle() }
    }

    private fun translateAll(items: List<Segment>) {
        val output = mutableListOf<Shown>()
        val provider = prefs.getInt("provider", 0)
        val configuredSource = sourceCodes[prefs.getInt("source", 0).coerceIn(sourceCodes.indices)]
        val target = targetCodes[prefs.getInt("target", 0).coerceIn(targetCodes.indices)]
        val detected = mutableMapOf<String, Int>()
        fun next(i: Int) {
            if (i >= items.size) {
                busy = false
                // Запомнить язык оригинала (для «Авто») — на него переводится введённый текст.
                detected.maxByOrNull { it.value }?.let { prefs.edit().putString("last_source", it.key).apply() }
                if (output.isEmpty()) fail("Перевод не получен. Проверьте язык, API-ключ и подключение.")
                else showResult(output)
                return
            }
            val item = items[i]
            fun translate(source: String) {
                detected[source] = (detected[source] ?: 0) + 1
                if (source == target) { output.add(Shown(item.rect, item.text, item.text)); next(i + 1); return }
                if (provider == 0) {
                    val key = "$source:$target"
                    val translator = try {
                        translators.getOrPut(key) {
                            Translation.getClient(TranslatorOptions.Builder()
                                .setSourceLanguage(source).setTargetLanguage(target).build())
                        }
                    } catch (e: Exception) { next(i + 1); return }
                    translator.downloadModelIfNeeded().addOnSuccessListener {
                        translator.translate(item.text).addOnSuccessListener { result ->
                            output.add(Shown(item.rect, item.text, result))
                            next(i + 1)
                        }.addOnFailureListener { next(i + 1) }
                    }.addOnFailureListener { next(i + 1) }
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
                            if (result != null) output.add(Shown(item.rect, item.text, result))
                            next(i + 1)
                        }
                    }.start()
                }
            }
            if (configuredSource != "auto") translate(configuredSource)
            else languageId.identifyLanguage(item.text).addOnSuccessListener { code ->
                val source = when {
                    item.text.any { it.code in 0x4E00..0x9FFF } && !item.text.any { it.code in 0x3040..0x30FF } -> "zh"
                    code.startsWith("zh") -> "zh"
                    code in arrayOf("en", "ja", "ko", "ru") -> code
                    else -> "en"
                }
                translate(source)
            }.addOnFailureListener { translate("zh") }
        }
        next(0)
    }

    private fun postForm(endpoint: String, params: Map<String, String>): JSONObject {
        val payload = params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}: ${body.take(120)}")
            return JSONObject(body)
        } finally { conn.disconnect() }
    }

    private fun googleTranslate(text: String, source: String, target: String): String {
        val key = prefs.getString("google_key", "").orEmpty()
        if (key.isBlank()) throw Exception("Не задан Google Cloud API key")
        val response = postForm("https://translation.googleapis.com/language/translate/v2?key=" +
            URLEncoder.encode(key, "UTF-8"),
            buildMap {
                put("q", text); put("target", target); put("format", "text")
                if (source != "auto") put("source", source)
            })
        return android.text.Html.fromHtml(
            response.getJSONObject("data").getJSONArray("translations")
                .getJSONObject(0).getString("translatedText"), android.text.Html.FROM_HTML_MODE_LEGACY
        ).toString()
    }

    private fun baiduTranslate(text: String, source: String, target: String): String {
        val appid = prefs.getString("baidu_appid", "").orEmpty()
        val secret = prefs.getString("baidu_secret", "").orEmpty()
        if (appid.isBlank() || secret.isBlank()) throw Exception("Не заданы Baidu App ID / Secret")
        val salt = System.currentTimeMillis().toString()
        val raw = appid + text + salt + secret
        val sign = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val result = postForm("https://fanyi-api.baidu.com/api/trans/vip/translate",
            mapOf("q" to text, "from" to source, "to" to target,
                "appid" to appid, "salt" to salt, "sign" to sign))
        if (result.has("error_code")) throw Exception("Baidu ${result.optString("error_code")}: ${result.optString("error_msg")}")
        return result.getJSONArray("trans_result").getJSONObject(0).getString("dst")
    }

    private fun fail(message: String) {
        busy = false
        bubble?.visibility = View.VISIBLE
        updateInputButton()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---------- Раскладка перевода поверх текста ----------

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        fun flush() { if (current.isNotEmpty()) { lines.add(current); current = "" } }
        for (paragraph in text.split('\n')) {
            for (word in paragraph.split(Regex("\\s+"))) {
                if (word.isEmpty()) continue
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (paint.measureText(candidate) <= width) { current = candidate; continue }
                flush()
                for (ch in word) {
                    if (current.isNotEmpty() && paint.measureText(current + ch) > width) flush()
                    current += ch
                }
            }
            flush()
        }
        return lines
    }

    private fun lineHeightFor(size: Float) = size * 1.15f

    /**
     * Каждый перевод стоит на месте своего оригинала. Рамка может расшириться вправо и вниз,
     * но только в свободное место до соседнего оригинала. Потом шрифт уменьшается, пока текст не влезет.
     */
    private fun layoutInPlace(items: List<Shown>, maxSize: Float, minSize: Float, screenW: Float, screenH: Float): List<Placed> {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val pad = dp(2f)
        val gap = dp(2f)
        val result = mutableListOf<Placed>()
        for (item in items) {
            val r = item.rect
            val others = items.filter { it !== item }.map { it.rect }
            // Вправо: до ближайшего оригинала справа на той же высоте или до края экрана.
            var right = screenW
            for (o in others) if (o.left >= r.right - 2 && o.top < r.bottom && o.bottom > r.top) right = min(right, o.left - gap)
            right = max(right, r.right.toFloat())
            // Вниз: не больше высоты ещё одной строки и до ближайшего оригинала ниже.
            var bottom = min(screenH, r.bottom + r.height() * 1.2f)
            for (o in others) if (o.top >= r.bottom - 2 && o.left < right && o.right > r.left) bottom = min(bottom, o.top - gap)
            bottom = max(bottom, r.bottom.toFloat())
            val box = RectF(r.left.toFloat(), r.top.toFloat(), right, bottom)
            val textW = (box.width() - 2 * pad).coerceAtLeast(dp(8f))
            val textH = (box.height() - 2 * pad).coerceAtLeast(dp(6f))
            // Самый крупный шрифт, при котором текст влезает. Не крупнее оригинала и настройки.
            var size = min(maxSize, max(minSize, r.height() * 0.85f))
            var lines: List<String> = emptyList()
            while (true) {
                paint.textSize = size
                lines = wrap(item.translated, paint, textW)
                if (lines.size * lineHeightFor(size) <= textH + 0.5f || size <= minSize) break
                size = max(minSize, size - 0.5f * resources.displayMetrics.scaledDensity)
            }
            if (lines.isEmpty()) continue
            // Если и минимальный шрифт не влез — обрезаем с «…», полный текст по тапу.
            val maxLines = max(1, (textH / lineHeightFor(size)).toInt())
            var truncated = false
            if (lines.size > maxLines) {
                truncated = true
                val kept = lines.take(maxLines).toMutableList()
                var last = kept.last()
                while (last.isNotEmpty() && paint.measureText("$last…") > textW) last = last.dropLast(1)
                kept[kept.size - 1] = "$last…"
                lines = kept
            }
            // Подгоняем высоту рамки под реальный текст, чтобы не закрывать лишнего.
            val usedH = lines.size * lineHeightFor(size) + 2 * pad
            val usedW = (lines.maxOf { paint.measureText(it) } + 2 * pad)
            val finalBox = RectF(box.left, box.top,
                max(r.right.toFloat(), min(box.right, box.left + usedW)),
                max(r.bottom.toFloat(), min(box.bottom, box.top + usedH)))
            result.add(Placed(finalBox, lines, size, truncated, item))
        }
        // Раскраска: соседние (касающиеся) блоки получают разные цвета.
        val touch = dp(4f)
        for ((i, b) in result.withIndex()) {
            val used = mutableSetOf<Int>()
            for (j in 0 until i) {
                val o = result[j].box
                if (RectF.intersects(RectF(b.box.left - touch, b.box.top - touch, b.box.right + touch, b.box.bottom + touch), o))
                    used.add(result[j].color)
            }
            b.color = (0..3).firstOrNull { it !in used } ?: (i % 4)
        }
        return result
    }

    private fun dismissDetail() {
        detailPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        detailPanel = null
    }

    private fun showDetail(item: Shown) {
        dismissDetail()
        val s = screenSize()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(14f).toInt(), dp(10f).toInt())
            setBackgroundColor(Color.argb(245, 255, 255, 255))
            addView(TextView(this@CaptureService).apply { text = item.original; setTextColor(Color.DKGRAY); textSize = 14f })
            addView(TextView(this@CaptureService).apply {
                text = item.translated; setTextColor(Color.BLACK); textSize = 17f
                setPadding(0, dp(6f).toInt(), 0, 0)
            })
            addView(TextView(this@CaptureService).apply { text = "нажмите, чтобы закрыть"; setTextColor(Color.GRAY); textSize = 11f })
            setOnClickListener { dismissDetail() }
        }
        detailPanel = panel
        wm.addView(panel, params(s.x, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            y = dp(48f).toInt()
        })
        bringBubbleToFront()
    }

    private fun showResult(items: List<Shown>) {
        bubble?.visibility = View.VISIBLE
        updateInputButton()
        if (items.isEmpty()) { Toast.makeText(this, "Перевод не получен. Проверьте интернет и модели.", Toast.LENGTH_LONG).show(); return }
        if (prefs.getInt("mode", 0) == 0) {
            val screen = screenSize()
            val screenW = screen.x.toFloat()
            val screenH = screen.y.toFloat()
            val sp = resources.displayMetrics.scaledDensity
            val maxSize = prefs.getInt("size", 14).toFloat() * sp
            val minSize = min(maxSize, 7f * sp)
            val blocks = layoutInPlace(items, maxSize, minSize, screenW, screenH)
            val dark = prefs.getInt("theme", 0) == 1
            val colored = prefs.getInt("colors", 0) == 0
            val palette = if (dark) intArrayOf(Color.rgb(20, 20, 20), Color.rgb(52, 46, 10), Color.rgb(12, 36, 62), Color.rgb(16, 50, 24))
                          else intArrayOf(Color.WHITE, Color.rgb(255, 244, 190), Color.rgb(212, 236, 255), Color.rgb(218, 245, 208))
            val closeR = dp(18f)
            val closeCx = screenW - dp(30f)
            val closeCy = dp(64f)

            val view = object : View(this) {
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.WHITE else Color.BLACK }
                val bg = Paint()
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeWidth = dp(1f)
                    color = if (dark) Color.rgb(110, 110, 110) else Color.rgb(150, 150, 150)
                }
                val more = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(230, 120, 0) }
                val closeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 60, 60, 60) }
                val closeFg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(2.5f) }
                override fun onDraw(canvas: Canvas) {
                    val loc = IntArray(2); getLocationOnScreen(loc)
                    canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
                    val pad = dp(2f)
                    for (b in blocks) {
                        bg.color = if (colored) palette[b.color] else palette[0]
                        canvas.drawRect(b.box, bg)
                        if (colored) canvas.drawRect(b.box, border)
                        textPaint.textSize = b.size
                        val lh = lineHeightFor(b.size)
                        for ((i, line) in b.lines.withIndex())
                            canvas.drawText(line, b.box.left + pad, b.box.top + pad + b.size * 0.92f + i * lh, textPaint)
                        // Оранжевый уголок: текст обрезан, тап покажет полностью.
                        if (b.truncated) {
                            val c = dp(7f)
                            val path = Path().apply {
                                moveTo(b.box.right, b.box.bottom - c); lineTo(b.box.right, b.box.bottom); lineTo(b.box.right - c, b.box.bottom); close()
                            }
                            canvas.drawPath(path, more)
                        }
                    }
                    canvas.drawCircle(closeCx, closeCy, closeR, closeBg)
                    val k = closeR * 0.45f
                    canvas.drawLine(closeCx - k, closeCy - k, closeCx + k, closeCy + k, closeFg)
                    canvas.drawLine(closeCx + k, closeCy - k, closeCx - k, closeCy + k, closeFg)
                }
            }
            view.setOnTouchListener(object : View.OnTouchListener {
                var startX = 0f
                var startY = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY }
                        MotionEvent.ACTION_UP -> {
                            val tap = hypot(event.rawX - startX, event.rawY - startY) < dp(10f)
                            if (tap && hypot(event.rawX - closeCx, event.rawY - closeCy) < closeR * 1.6f) { dismissResult(); return true }
                            if (startY - event.rawY > dp(45f)) { dismissResult(); return true }
                            if (tap) {
                                // Сверху нарисованы последние блоки — ищем с конца.
                                val hit = blocks.lastOrNull { it.box.contains(event.rawX, event.rawY) }
                                if (hit != null) showDetail(hit.item) else dismissDetail()
                            }
                        }
                    }
                    return true
                }
            })
            resultOverlay = view
            wm.addView(view, fullScreenParams())
            bringBubbleToFront()
        } else {
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 12, 16, 12); setBackgroundColor(Color.WHITE) }
            layout.addView(Button(this).apply { text = "Закрыть ✕"; setOnClickListener { dismissResult() } })
            val scroll = ScrollView(this)
            scroll.addView(TextView(this).apply { text = items.joinToString("\n\n") { it.translated }; setTextColor(Color.BLACK); textSize = prefs.getInt("size", 14).toFloat() })
            layout.addView(scroll)
            resultOverlay = layout
            val s = screenSize()
            wm.addView(layout, params(s.x * 9 / 10, s.y / 2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            bringBubbleToFront()
        }
    }

    // ---------- Перевод введённого текста (кнопка «RU→ZH») ----------

    /** Вызывается службой ввода: курсор в поле ввода другого приложения (true) или нет. */
    fun setInputActive(active: Boolean) {
        handler.post { inputActive = active; updateInputButton() }
    }

    /** Языки для ввода: с языка перевода (обычно русский) на язык оригинала. */
    private fun inputLangs(): Pair<String, String> {
        val configured = sourceCodes[prefs.getInt("source", 0).coerceIn(sourceCodes.indices)]
        val to = if (configured == "auto") prefs.getString("last_source", "zh") ?: "zh" else configured
        val from = targetCodes[prefs.getInt("target", 0).coerceIn(targetCodes.indices)]
        return from to to
    }

    private fun updateInputButton() {
        if (!::wm.isInitialized) return
        val show = inputActive && !busy && holdRect == null && fallbackWindow == null &&
            bubble != null && bubble?.visibility == View.VISIBLE
        if (!show) { inputButton?.visibility = View.GONE; return }
        val (from, to) = inputLangs()
        val b = inputButton ?: Button(this).apply {
            textSize = 12f
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setPadding(dp(8f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt())
            setOnClickListener { translateInput() }
        }.also { btn ->
            val ip = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            inputButton = btn; inputParams = ip
            wm.addView(btn, ip)
        }
        if (!inputBusy) b.text = "${from.uppercase()}→${to.uppercase()}"
        b.visibility = View.VISIBLE
        positionInputButton()
    }

    /** Кнопка ввода стоит под TR (или над ним, если внизу нет места). */
    private fun positionInputButton() {
        val b = inputButton ?: return
        val bp = bubbleParams ?: return
        val ip = inputParams ?: return
        val side = dp(52f).toInt()
        val h = dp(40f).toInt()
        val gap = dp(4f).toInt()
        ip.x = bp.x
        ip.y = if (bp.y + side + gap + h < screenSize().y) bp.y + side + gap else bp.y - h - gap
        try { wm.updateViewLayout(b, ip) } catch (_: Exception) {}
    }

    private fun translateInput() {
        if (inputBusy) return
        val (from, to) = inputLangs()
        if (from == to) {
            Toast.makeText(this, "Язык перевода совпадает с языком оригинала — измените язык в настройках", Toast.LENGTH_LONG).show()
            return
        }
        val service = InputAssistService.instance
        val original = service?.readFocusedText()
        if (original.isNullOrBlank()) { showFallback("", from, to, null); return }
        inputBusy = true
        inputButton?.text = "…"
        translateText(original, from, to) { result, error ->
            inputBusy = false
            updateInputButton()
            when {
                result == null -> Toast.makeText(this, "Перевод не получен: ${error ?: "проверьте подключение и модели"}", Toast.LENGTH_LONG).show()
                InputAssistService.instance?.replaceFocusedText(result) == true -> {}
                else -> showFallback(original, from, to, result)
            }
        }
    }

    /** Перевод одной строки выбранным переводчиком (ML Kit / Google / Baidu). */
    private fun translateText(text: String, from: String, to: String, done: (String?, String?) -> Unit) {
        val provider = prefs.getInt("provider", 0)
        if (provider == 0) {
            val translator = try {
                translators.getOrPut("$from:$to") {
                    Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
                }
            } catch (e: Exception) { done(null, e.message); return }
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { done(it, null) }
                        .addOnFailureListener { done(null, it.message) }
                }
                .addOnFailureListener { done(null, "модель не загружена: ${it.message}") }
        } else {
            Thread {
                var result: String? = null
                var error: String? = null
                try {
                    result = if (provider == 1) googleTranslate(text, from, to) else baiduTranslate(text, from, to)
                } catch (e: Exception) { error = e.message }
                handler.post { done(result, error) }
            }.start()
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("translation", text))
    }

    private fun dismissFallback() {
        fallbackWindow?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        fallbackWindow = null
    }

    /**
     * Запасное окно: если поле не дало вставить текст (или оно пустое).
     * Можно написать или поправить текст, перевести и скопировать перевод в буфер обмена.
     */
    private fun showFallback(original: String, from: String, to: String, result: String?) {
        dismissFallback()
        val pad = dp(10f).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Перевод ввода ${from.uppercase()} → ${to.uppercase()}"
            setTextColor(Color.DKGRAY); textSize = 13f
        })
        val input = EditText(this).apply {
            setText(original)
            hint = "Введите текст"
            setTextColor(Color.BLACK); setHintTextColor(Color.GRAY); textSize = 16f
            maxLines = 4
        }
        root.addView(input)
        val output = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 18f
            setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
            setTextIsSelectable(true)
        }
        root.addView(output)
        val note = TextView(this).apply { setTextColor(Color.rgb(200, 90, 0)); textSize = 12f }
        root.addView(note)
        fun setResult(text: String) {
            output.text = text
            copyToClipboard(text)
            note.text = "Скопировано. Вставьте в поле: долгое нажатие → «Вставить»."
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Перевести"
            setOnClickListener {
                val t = input.text.toString().trim()
                if (t.isEmpty()) return@setOnClickListener
                note.text = "Перевожу…"
                translateText(t, from, to) { r, e ->
                    if (r == null) note.text = "Ошибка: ${e ?: "нет перевода"}"
                    if (r != null) setResult(r)
                }
            }
        })
        row.addView(Button(this).apply {
            text = "Копировать"
            setOnClickListener { if (output.text.isNotEmpty()) setResult(output.text.toString()) }
        })
        row.addView(Button(this).apply {
            text = "✕"
            setOnClickListener { dismissFallback(); updateInputButton() }
        })
        root.addView(row)
        if (result != null) {
            setResult(result)
            note.text = "Поле не дало вставить текст. Перевод скопирован — вставьте его долгим нажатием → «Вставить»."
        }
        val s = screenSize()
        val lp = params((s.x * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            y = dp(70f).toInt()
            // Окно должно получать фокус, чтобы в нём можно было печатать.
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        fallbackWindow = root
        wm.addView(root, lp)
        updateInputButton()
        if (result == null) input.requestFocus()
    }

    override fun onDestroy() {
        instance = null
        inputButton?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        dismissFallback()
        holdRunnable?.let { handler.removeCallbacks(it) }
        removeSelectionHint()
        dismissResult()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        display?.release(); reader?.close(); projection?.stop(); recognizer?.close()
        languageId.close(); translators.values.forEach { it.close() }
        super.onDestroy()
    }
}
