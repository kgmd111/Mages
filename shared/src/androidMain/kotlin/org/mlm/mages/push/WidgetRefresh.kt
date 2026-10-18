package org.mlm.mages.push

import android.content.Context
import android.content.Intent

object WidgetRefresh {

    const val ACTION = "org.mlm.mages.action.WIDGET_REFRESH"

    fun request(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
        }
    }
}
