package com.hako.player

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Afiş kutusu: yükseklik genişliğin 1,5 katı (2:3 film afişi oranı), afiş kırpılmadan tam görünür */
class PosterImageView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(ctx, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(w * 3 / 2, MeasureSpec.EXACTLY))
    }
}
