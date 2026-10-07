package com.raptibooks.core.data

import android.content.Context
import com.raptibooks.core.model.OcrMode
import com.raptibooks.core.model.ReaderFont
import com.raptibooks.core.model.ReaderSettings
import com.raptibooks.core.model.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reader and app preferences, persisted in SharedPreferences and exposed as state. */
class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _reader = MutableStateFlow(load())
    val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()

    private val _defaultOcrMode = MutableStateFlow(
        runCatching { OcrMode.valueOf(prefs.getString("ocr_mode", OcrMode.AUTO.name)!!) }.getOrDefault(OcrMode.AUTO),
    )
    val defaultOcrMode: StateFlow<OcrMode> = _defaultOcrMode.asStateFlow()

    fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(_reader.value).let {
            it.copy(
                fontSize = it.fontSize.coerceIn(ReaderSettings.MIN_FONT_SIZE, ReaderSettings.MAX_FONT_SIZE),
                lineSpacing = it.lineSpacing.coerceIn(ReaderSettings.MIN_LINE_SPACING, ReaderSettings.MAX_LINE_SPACING),
                margin = it.margin.coerceIn(ReaderSettings.MIN_MARGIN, ReaderSettings.MAX_MARGIN),
            )
        }
        _reader.value = next
        prefs.edit()
            .putFloat("font_size", next.fontSize)
            .putString("font", next.font.name)
            .putFloat("line_spacing", next.lineSpacing)
            .putInt("margin", next.margin)
            .putString("theme", next.theme.name)
            .putBoolean("justify", next.justify)
            .putBoolean("keep_screen_on", next.keepScreenOn)
            .apply()
    }

    fun setDefaultOcrMode(mode: OcrMode) {
        _defaultOcrMode.value = mode
        prefs.edit().putString("ocr_mode", mode.name).apply()
    }

    private fun load(): ReaderSettings {
        val d = ReaderSettings()
        return ReaderSettings(
            fontSize = prefs.getFloat("font_size", d.fontSize),
            font = runCatching { ReaderFont.valueOf(prefs.getString("font", d.font.name)!!) }.getOrDefault(d.font),
            lineSpacing = prefs.getFloat("line_spacing", d.lineSpacing),
            margin = prefs.getInt("margin", d.margin),
            theme = runCatching { ReaderTheme.valueOf(prefs.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            justify = prefs.getBoolean("justify", d.justify),
            keepScreenOn = prefs.getBoolean("keep_screen_on", d.keepScreenOn),
        )
    }
}
