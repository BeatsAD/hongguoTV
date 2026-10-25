// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.widget.ImageView

/** Every poster retains its complete original content, including printed titles and faces. */
class CinematicArtwork(context: Context): ImageView(context) {
    init { scaleType=ScaleType.FIT_CENTER }
}
