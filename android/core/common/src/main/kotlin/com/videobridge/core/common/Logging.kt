package com.videobridge.core.common

import timber.log.Timber

/**
 * Call once from Application.onCreate. Release builds plant no tree, so nothing is logged there.
 * Never log tokens, OTPs or URLs with query strings, in any build.
 */
fun initLogging(isDebug: Boolean) {
    if (isDebug && Timber.treeCount == 0) {
        Timber.plant(Timber.DebugTree())
    }
}
