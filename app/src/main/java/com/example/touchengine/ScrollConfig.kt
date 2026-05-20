package com.example.touchengine

import android.content.Context

/**
 * 自动滚动配置
 *
 * 所有字段都对应 SharedPreferences 里的一个 key，
 * TouchEngineService 启动时读取，用户在 AdvancedScreen 修改后实时写入。
 */
data class ScrollConfig(
    /** 总开关：是否启用自动滚动/翻页 */
    val autoScrollEnabled: Boolean = true,

    /** 方案A等待时间（毫秒）：到边缘后等多久尝试 ACTION_SCROLL */
    val scrollTriggerDelayMs: Long = 600L,

    /** 提示显示时间（毫秒）：方案A失败后提示条显示多久再触发方案B */
    val hintDurationMs: Long = 1500L,

    /** 方案B灵敏度倍率：控制单次模拟滑动的距离（0.5 = 半屏，1.0 = 全屏）*/
    val scrollSensitivity: Float = 0.5f,

    /** 方案B单次手势时长（毫秒）：越短滑动越快 */
    val scrollGestureDurationMs: Long = 300L,

    /** 连续几帧 findBestNeighbor=null 才判定为"到边缘了" */
    val edgeTriggerFrames: Int = 6
) {
    companion object {
        private const val PREFS_NAME = "TouchEnginePrefs"

        private const val KEY_AUTO_SCROLL_ENABLED       = "auto_scroll_enabled"
        private const val KEY_SCROLL_TRIGGER_DELAY      = "scroll_trigger_delay_ms"
        private const val KEY_HINT_DURATION             = "hint_duration_ms"
        private const val KEY_SCROLL_SENSITIVITY        = "scroll_sensitivity"
        private const val KEY_SCROLL_GESTURE_DURATION   = "scroll_gesture_duration_ms"
        private const val KEY_EDGE_TRIGGER_FRAMES       = "edge_trigger_frames"

        /** 从 SharedPreferences 读取配置 */
        fun load(context: Context): ScrollConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ScrollConfig(
                autoScrollEnabled     = prefs.getBoolean(KEY_AUTO_SCROLL_ENABLED, true),
                scrollTriggerDelayMs  = prefs.getLong(KEY_SCROLL_TRIGGER_DELAY, 600L),
                hintDurationMs        = prefs.getLong(KEY_HINT_DURATION, 1500L),
                scrollSensitivity     = prefs.getFloat(KEY_SCROLL_SENSITIVITY, 0.5f),
                scrollGestureDurationMs = prefs.getLong(KEY_SCROLL_GESTURE_DURATION, 300L),
                edgeTriggerFrames     = prefs.getInt(KEY_EDGE_TRIGGER_FRAMES, 6)
            )
        }

        /** 保存配置到 SharedPreferences */
        fun save(context: Context, config: ScrollConfig) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_SCROLL_ENABLED,       config.autoScrollEnabled)
                .putLong(KEY_SCROLL_TRIGGER_DELAY,         config.scrollTriggerDelayMs)
                .putLong(KEY_HINT_DURATION,                config.hintDurationMs)
                .putFloat(KEY_SCROLL_SENSITIVITY,          config.scrollSensitivity)
                .putLong(KEY_SCROLL_GESTURE_DURATION,      config.scrollGestureDurationMs)
                .putInt(KEY_EDGE_TRIGGER_FRAMES,           config.edgeTriggerFrames)
                .apply()
        }
    }
}