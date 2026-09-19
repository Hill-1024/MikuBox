package com.neko.marquee.text

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import top.uwu.mikubox.R
import top.uwu.mikubox.core.AppSettings

class UserNameTextView : AppCompatTextView {

    private var mAggregatedVisible: Boolean = false

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init()
    }

    private fun init() {
        mAggregatedVisible = false
        updateTextFromSettings()
    }

    private fun updateTextFromSettings() {
        val name = AppSettings.profileName(context)
        // Same semantics as BaseUwuSheet.profileName: the title string is the
        // full greeting used when no custom name is set, not a bare name.
        val finalString = if (name.isBlank()) {
            context.getString(R.string.uwu_profile_banner_title)
        } else {
            context.getString(R.string.uwu_profile_banner_title_custom, name)
        }

        if (text.toString() != finalString) {
            text = finalString

            isSelected = false
            isSelected = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isSelected = true
        updateTextFromSettings()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            updateTextFromSettings()
            isSelected = true
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isSelected = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        onVisibilityAggregated(isVisibleToUser())
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == mAggregatedVisible) {
            return
        }
        mAggregatedVisible = isVisible
        if (mAggregatedVisible) {
            ellipsize = TextUtils.TruncateAt.MARQUEE
        } else {
            ellipsize = TextUtils.TruncateAt.END
        }
    }

    fun View.isVisibleToUser(): Boolean {
        return this.visibility == View.VISIBLE
    }
}
