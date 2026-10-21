package com.example.screentranslator

import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import com.google.mlkit.nl.translate.*

class MainActivity : Activity() {
    private val requestCapture = 1001
    private var mode = "Окно"
    private val translator by lazy { Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.RUSSIAN).build()) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,48,32,32) }
        root.addView(TextView(this).apply { text = "Screen Translator v0.3"; textSize = 25f })
        root.addView(TextView(this).apply { text = "Перевод с экрана без рекламы. Китайский → Русский.\nМодель перевода скачивается отдельно после установки."; textSize = 16f })
        val choices = arrayOf("Окно", "Поверх текста (экспериментально)")
        root.addView(Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, choices); onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { mode = if(position == 0) "Окно" else "Поверх" }
        } })
        val status = TextView(this).apply {
            text = if (getSharedPreferences("models", MODE_PRIVATE).getBoolean("zh_ru_ready", false)) "Модель перевода: загружена" else "Модель перевода: не загружена"
            textSize = 16f
        }
        root.addView(status)
        val downloadButton = Button(this).apply { text = "Скачать модель: китайский → русский" }
        root.addView(downloadButton)
        downloadButton.setOnClickListener {
            downloadButton.isEnabled = false
            status.text = "Загрузка модели… требуется интернет"
            translator.downloadModelIfNeeded().addOnSuccessListener {
                getSharedPreferences("models", MODE_PRIVATE).edit().putBoolean("zh_ru_ready", true).apply()
                status.text = "Модель перевода: загружена. Можно запускать переводчик."
                downloadButton.isEnabled = true
            }.addOnFailureListener { error ->
                status.text = "Ошибка загрузки: ${error.localizedMessage ?: "проверьте интернет"}"
                downloadButton.isEnabled = true
            }
        }
        root.addView(Button(this).apply { text = "1. Разрешить плавающие окна"; setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } })
        root.addView(Button(this).apply { text = "2. Запустить переводчик"; setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) { Toast.makeText(this@MainActivity,"Сначала разрешите плавающие окна",Toast.LENGTH_LONG).show(); return@setOnClickListener }
            getSystemService(MediaProjectionManager::class.java).let { startActivityForResult(it.createScreenCaptureIntent(), requestCapture) }
        } })
        root.addView(Button(this).apply { text = "Остановить"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) } })
        root.addView(TextView(this).apply { text = "После запуска появится плавающая кнопка TR. Нажмите её в другом приложении, чтобы перевести видимый текст. Для захвата Android потребует согласие." })
        setContentView(root)
    }
    @Deprecated("Legacy result API for minimal project")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("data",data).putExtra("mode",mode)
            startForegroundService(intent)
            moveTaskToBack(true)
        }
    }
}
