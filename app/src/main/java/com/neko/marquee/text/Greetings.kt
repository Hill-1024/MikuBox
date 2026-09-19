package com.neko.marquee.text

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.TextUtils
import android.util.AttributeSet
import androidx.annotation.StringRes
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import top.uwu.mikubox.R
import java.util.Calendar

/**
 * The clock-driven greeting line of the banner. Port of the UwU view with the
 * weather block removed: no location, no network — only the time-of-day
 * greeting that refreshes on every minute tick.
 */
class Greetings @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> updateDisplay()
            }
        }
    }

    init {
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSingleLine = true
        isSelected = true
        isFocusable = true
        isFocusableInTouchMode = true
        freezesText = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isSelected = true

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        updateDisplay()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            context.unregisterReceiver(timeReceiver)
        } catch (e: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            updateDisplay()
            isSelected = true
        }
    }

    private fun updateDisplay() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        @StringRes val greetRes = when (hour) {
            in 5..10 -> R.string.uwu_greeting_morning
            in 11..14 -> R.string.uwu_greeting_afternoon
            in 15..18 -> R.string.uwu_greeting_evening
            in 19..23 -> R.string.uwu_greeting_night
            else -> R.string.uwu_greeting_late_night
        }

        text = context.getString(greetRes)

        isSelected = false
        isSelected = true
    }
}
