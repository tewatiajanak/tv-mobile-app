package com.videobridge.core.player

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import javax.inject.Inject

/** Creates players. The caller owns the returned player and must release it. */
interface PlayerFactory {
    fun create(context: Context): ExoPlayer
}

/** Plain ExoPlayer for now; buffering, data sources and audio focus are tuned in Phase 5. */
class DefaultPlayerFactory
@Inject
constructor() : PlayerFactory {
    override fun create(context: Context): ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()
}
