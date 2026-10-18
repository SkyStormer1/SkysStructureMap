package com.skystormer.skysstructuremap.gui

import kotlin.math.roundToInt

/** The sums behind every scroll bar here: the thumb's size and place, and where dragging it scrolls to. */
internal object ScrollBar {

    /** The thumb's height on a [track] showing [shown] of [total], never under [least] nor over the track. */
    fun thumbHeight(track: Int, shown: Int, total: Int, least: Int = 16): Int =
        (track * shown / maxOf(1, total)).coerceIn(minOf(least, track), track)

    /** The thumb's top on a track from [trackTop], scrolled [scroll] of [most]. */
    fun thumbTop(trackTop: Int, track: Int, thumb: Int, scroll: Int, most: Int): Int =
        trackTop + if (most > 0) (track - thumb) * scroll / most else 0

    /** The scroll that puts the thumb's point [grab] down from its top at [mouseY]. */
    fun scrollFor(mouseY: Double, trackTop: Int, track: Int, thumb: Int, grab: Double, most: Int): Int =
        ((mouseY - trackTop - grab) / maxOf(1, track - thumb) * most).roundToInt().coerceIn(0, maxOf(0, most))
}
