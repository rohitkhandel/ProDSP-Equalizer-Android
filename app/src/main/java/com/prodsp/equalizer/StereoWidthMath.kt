package com.prodsp.equalizer

/** Allocation-free mid/side stereo-width equations. */
object StereoWidthMath {
    /**
     * 100% is unchanged stereo, 0% is mono, and 200% doubles the side signal.
     */
    fun left(left: Float, right: Float, widthPercent: Float): Float {
        val width = widthPercent.coerceIn(0f, 200f) / 100f
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        return mid + side * width
    }

    fun right(left: Float, right: Float, widthPercent: Float): Float {
        val width = widthPercent.coerceIn(0f, 200f) / 100f
        val mid = (left + right) * 0.5f
        val side = (left - right) * 0.5f
        return mid - side * width
    }

    /** Attenuates only the opposite channel, without boosting the chosen side. */
    fun leftBalanceGain(balancePercent: Float): Float =
        if (balancePercent > 0f) 1f - balancePercent.coerceIn(0f, 100f) / 100f else 1f

    fun rightBalanceGain(balancePercent: Float): Float =
        if (balancePercent < 0f) 1f + balancePercent.coerceIn(-100f, 0f) / 100f else 1f
}
