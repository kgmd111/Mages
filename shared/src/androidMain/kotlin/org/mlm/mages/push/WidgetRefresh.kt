package org.mlm.mages.push

import android.content.Context
import android.content.Intent

/**
 * Pokes the home-screen widget so it re-reads the persisted room list
 * whenever notification state changes. Broadcast only: the widget lives in
 * the app module, which depends on this one, not the other way around.
 */
object WidgetRefresh {

    const val ACTION = "org.mlm.mages.action.WIDGET_REFRESH"

    fun request(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
        }
    }
}
