package app.gaborbiro.permutator

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import app.gaborbiro.permutator.uptime.UptimeService.Companion.getScanStartIntent

class InternetWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val pendingIntent: PendingIntent = PendingIntent.getService(
                context,
                0,
                context.getScanStartIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT
            )

            val views: RemoteViews = RemoteViews(
                context.packageName,
                R.layout.internet_widget
            ).apply {
//                setImageViewResource(R.id.icon, R.drawable.ic_cloud_filled)
                setOnClickPendingIntent(R.id.icon, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}