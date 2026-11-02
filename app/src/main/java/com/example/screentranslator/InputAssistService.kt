package com.example.screentranslator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Служба специальных возможностей для перевода введённого текста.
 * Следит, стоит ли курсор в поле ввода другого приложения (и открыта ли клавиатура),
 * сообщает об этом CaptureService (он показывает кнопку «RU→ZH»),
 * читает текст из поля и заменяет его переводом.
 */
class InputAssistService : AccessibilityService() {
    companion object {
        @Volatile var instance: InputAssistService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { evaluate() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        evaluate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События приходят пачками — проверяем состояние с небольшой задержкой.
        handler.removeCallbacks(check)
        handler.postDelayed(check, 150)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(check)
        CaptureService.instance?.setInputActive(false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(check)
        CaptureService.instance?.setInputActive(false)
        super.onDestroy()
    }

    /** Поле ввода другого приложения, в котором сейчас курсор. Пароли и наши окна пропускаем. */
    private fun focusedInput(): AccessibilityNodeInfo? {
        val node = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null } ?: return null
        if (!node.isEditable || node.isPassword) return null
        if (node.packageName?.toString() == packageName) return null
        return node
    }

    /** true/false — открыта ли клавиатура; null — система не сообщила список окон. */
    private fun keyboardVisible(): Boolean? = try {
        val list = windows
        if (list.isNullOrEmpty()) null else list.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (_: Exception) { null }

    private fun evaluate() {
        val active = focusedInput() != null && keyboardVisible() != false
        CaptureService.instance?.setInputActive(active)
    }

    /** Текст из поля ввода. Пустая строка, если поле пустое (показана только подсказка). null — поля нет. */
    fun readFocusedText(): String? {
        val node = focusedInput() ?: return null
        if (Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) return ""
        return node.text?.toString() ?: ""
    }

    /** Заменить текст в поле ввода. Возвращает false, если поле не приняло текст. */
    fun replaceFocusedText(newText: String): Boolean {
        val node = focusedInput() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        // Проверяем, что текст действительно заменился (некоторые поля игнорируют команду).
        node.refresh()
        val now = node.text?.toString()
        if (now != null && now.trim() != newText.trim()) return false
        // Курсор в конец текста.
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return true
    }
}
