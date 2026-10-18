package org.mlm.mages.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.mlm.mages.R

class MagesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        MagesWidgetFactory(applicationContext)
}

class MagesWidgetFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var rooms: List<WidgetRoom> = emptyList()

    override fun onCreate() = reload()

    override fun onDataSetChanged() = reload()

    private fun reload() {
        rooms = MagesWidgetData.loadUnreadRooms(context)
    }

    override fun onDestroy() {
        rooms = emptyList()
    }

    override fun getCount(): Int = rooms.size

    override fun getViewAt(position: Int): RemoteViews? {
        val room = rooms.getOrNull(position) ?: return null
        val row = RemoteViews(context.packageName, R.layout.mages_widget_row)
        row.setTextViewText(R.id.widget_room_name, room.name)
        row.setTextViewText(
            R.id.widget_room_badge,
            if (room.unread > 0) room.unread.toString() else "•",
        )
        val fillIn = Intent().apply {
            data = Uri.parse("mages://room?id=${Uri.encode(room.roomId)}")
        }
        row.setOnClickFillInIntent(R.id.widget_row, fillIn)
        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = rooms.getOrNull(position)?.roomId?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
