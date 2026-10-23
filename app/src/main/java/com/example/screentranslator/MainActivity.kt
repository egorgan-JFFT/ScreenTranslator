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
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 36, 28, 24) }
        val scroll = ScrollView(this).apply { addView(root) }
        root.addView(TextView(this).apply { text = "Screen Translator v0.5"; textSize = 24f })
        root.addView(TextView(this).apply { text = "Автоопределение языка → Русский. Основной режим — поверх текста."; textSize = 15f })
        fun spinner(title: String, items: Array<String>, key: String, default: Int) {
            root.addView(TextView(this).apply { text = title; textSize = 16f; setPadding(0, 18, 0, 0) })
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            sp.setSelection(prefs.getInt(key, default).coerceIn(items.indices))
            sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.edit().putInt(key, position).apply()
                }
            }
            root.addView(sp)
        }
        spinner("Режим отображения", arrayOf("Поверх текста", "Отдельное окно"), "mode", 0)
        
        spinner("Цвет перевода", arrayOf("Чёрный текст / белый фон", "Белый текст / чёрный фон"), "theme", 0)
        root.addView(TextView(this).apply { text = "Размер текста"; textSize = 16f; setPadding(0, 18, 0, 0) })
        val sizeLabel = TextView(this)
        val seek = SeekBar(this).apply { max = 20; progress = (prefs.getInt("size", 14) - 10).coerceIn(0, 20) }
        sizeLabel.text = "${seek.progress + 10} sp"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { sizeLabel.text = "${value + 10} sp"; prefs.edit().putInt("size", value + 10).apply() }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        root.addView(sizeLabel); root.addView(seek)
        root.addView(TextView(this).apply { text = "При первом переводе нового языка потребуется интернет для загрузки модели. После загрузки она используется локально."; textSize = 13f })
        val status = TextView(this).apply { text = "Для китайского языка можно заранее загрузить модель:"; textSize = 14f }
        root.addView(status)
        val button = Button(this).apply { text = "Скачать китайский → русский" }
        root.addView(button)
        button.setOnClickListener {
            button.isEnabled = false
            status.text = "Загружаем модель…"
            val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.RUSSIAN).build())
            translator.downloadModelIfNeeded().addOnSuccessListener { status.text = "Китайская модель готова" }
                .addOnFailureListener { status.text = "Ошибка: ${it.localizedMessage}" }
                .addOnCompleteListener { translator.close(); button.isEnabled = true }
        }
        root.addView(Button(this).apply { text = "1. Разрешить плавающие окна"; setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } })
        root.addView(Button(this).apply { text = "2. Запустить переводчик"; setOnClickListener {
            if (!Settings.canDrawOverlays(this@MainActivity)) { Toast.makeText(this@MainActivity, "Разрешите плавающие окна", Toast.LENGTH_LONG).show(); return@setOnClickListener }
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), requestCapture)
        } })
        root.addView(Button(this).apply { text = "Остановить"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) } })
        root.addView(TextView(this).apply { text = "Нажмите плавающую TR для перевода. В режиме области обведите нужный участок пальцем. Нажатие на перевод скрывает его."; textSize = 14f })
        setContentView(scroll)
    }
    @Deprecated("Legacy result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("data", data))
            moveTaskToBack(true)
        }
    }
}
