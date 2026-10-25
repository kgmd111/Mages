package org.mlm.mages.push

import android.media.AudioDeviceInfo

fun interface CallAudioRouteHandler {
    fun requestRoute(device: AudioDeviceInfo): Boolean
}

object CallAudioRouteBridge {
    @Volatile
    var handler: CallAudioRouteHandler? = null
}
