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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.nl.translate.*

class CaptureService : Service() {
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var bubble: View? = null
    private var resultOverlay: View? = null
    private var mode = "Окно"
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.RUSSIAN).build())
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("capture", "Перевод экрана", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val note = Notification.Builder(this,"capture").setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Screen Translator").setContentText("Захват экрана активен").build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1,note)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mode = intent?.getStringExtra("mode") ?: "Окно"
        val data = if(Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED, data)
        projection?.registerCallback(object: MediaProjection.Callback() { override fun onStop() { handler.post { stopSelf() } } },handler)
        val metrics = resources.displayMetrics
        reader = ImageReader.newInstance(metrics.widthPixels,metrics.heightPixels,android.graphics.PixelFormat.RGBA_8888,2)
        display = projection?.createVirtualDisplay("TranslateCapture",metrics.widthPixels,metrics.heightPixels,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
        showBubble()
        return START_NOT_STICKY
    }
    private fun params(width:Int,height:Int,gravity:Int=Gravity.TOP or Gravity.START) = WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,android.graphics.PixelFormat.TRANSLUCENT).apply { this.gravity=gravity }
    private fun showBubble() {
        val button = Button(this).apply { text="TR"; setOnClickListener { capture() } }
        val p = params(155,125).apply { x=0; y=230 }
        button.setOnTouchListener(object: View.OnTouchListener {
            var downX=0f; var downY=0f; var oldX=0; var oldY=0; var moved=false
            override fun onTouch(v:View,event:android.view.MotionEvent):Boolean {
                when(event.action) {
                    MotionEvent.ACTION_DOWN -> {downX=event.rawX;downY=event.rawY;oldX=p.x;oldY=p.y;moved=false;return true}
                    MotionEvent.ACTION_MOVE -> {if(kotlin.math.abs(event.rawX-downX)>10 || kotlin.math.abs(event.rawY-downY)>10) moved=true; if(moved){p.x=oldX+(event.rawX-downX).toInt();p.y=oldY+(event.rawY-downY).toInt();wm.updateViewLayout(button,p)};return true}
                    MotionEvent.ACTION_UP -> {if(!moved) v.performClick();return true}
                };return false
            }
        })
        bubble=button; wm.addView(button,p)
    }
    private fun capture() {
        if(busy)return
        if (!getSharedPreferences("models", MODE_PRIVATE).getBoolean("zh_ru_ready", false)) {
            Toast.makeText(this, "Сначала скачайте модель перевода в приложении", Toast.LENGTH_LONG).show()
            return
        }
        busy=true
        bubble?.visibility=View.INVISIBLE
        resultOverlay?.let { wm.removeView(it) }; resultOverlay=null
        handler.postDelayed({
            val image = reader?.acquireLatestImage()
            if(image==null) { finishError("Не удалось получить кадр");return@postDelayed }
            try {
                val plane=image.planes[0];val buffer=plane.buffer
                val pixelStride=plane.pixelStride;val rowStride=plane.rowStride
                val padded=Bitmap.createBitmap(image.width+(rowStride-pixelStride*image.width)/pixelStride,image.height,Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(buffer)
                val bitmap=Bitmap.createBitmap(padded,0,0,image.width,image.height)
                padded.recycle()
                recognizer.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener { result ->
                    val lines=result.textBlocks.flatMap { it.lines }.filter { it.text.isNotBlank() }
                    if(lines.isEmpty()) { finishError("Текст не найден");return@addOnSuccessListener }
                    run {
                        val translations=mutableListOf<Pair<android.graphics.Rect,String>>()
                        fun next(index:Int) {
                            if(index>=lines.size) { showResult(translations);busy=false;return }
                            val line=lines[index]
                            translator.translate(line.text).addOnSuccessListener { translated ->
                                translations.add(Pair(line.boundingBox ?: android.graphics.Rect(0,0,0,0),translated));next(index+1)
                            }.addOnFailureListener { next(index+1) }
                        };next(0)
                    }
                }.addOnFailureListener { finishError("Ошибка OCR: ${it.message}") }.addOnCompleteListener { bitmap.recycle() }
            } finally {image.close()}
        },400)
    }
    private fun finishError(message:String) { busy=false;bubble?.visibility=View.VISIBLE;Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun showResult(items:List<Pair<android.graphics.Rect,String>>) {
        bubble?.visibility=View.VISIBLE
        if(mode=="Поверх") {
            val view=object:View(this) {
                val paint=Paint(3).apply { color=Color.WHITE; textSize=36f }
                val background=Paint().apply { color=Color.rgb(30,30,30) }
                override fun onDraw(canvas:Canvas) {
                    super.onDraw(canvas)
                    for((rect,text) in items) {
                        val left=rect.left.toFloat();val top=rect.top.toFloat();val right=kotlin.math.max(rect.right.toFloat(),left+paint.measureText(text)+12)
                        canvas.drawRect(left,top,right,top+44,background)
                        canvas.drawText(text,left+4,top+35,paint)
                    }
                }
            }
            view.setOnClickListener { wm.removeView(view);resultOverlay=null }
            resultOverlay=view
            wm.addView(view,params(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT))
        } else {
            val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(20,12,20,12);setBackgroundColor(Color.rgb(35,35,35)) }
            layout.addView(Button(this).apply { text="Закрыть перевод ✕";setOnClickListener { resultOverlay?.let { wm.removeView(it) };resultOverlay=null } })
            val scroll=ScrollView(this)
            scroll.addView(TextView(this).apply { text=items.joinToString("\n\n") { it.second };setTextColor(Color.WHITE);textSize=17f })
            layout.addView(scroll)
            resultOverlay=layout
            wm.addView(layout,params(resources.displayMetrics.widthPixels*9/10,resources.displayMetrics.heightPixels/2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
    }
    override fun onDestroy() {
        resultOverlay?.let { wm.removeView(it) };bubble?.let { wm.removeView(it) }
        display?.release();reader?.close();projection?.stop();recognizer.close();translator.close()
        super.onDestroy()
    }
}
