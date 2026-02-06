package com.projectmanager.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.projectmanager.app.MainActivity
import com.projectmanager.app.R
import com.projectmanager.app.data.database.AppDatabase
import com.projectmanager.app.repository.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DailySummaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val database = AppDatabase.getDatabase(context)
        val repository = ProjectRepository(database.projectDao(), database.taskDao())

        CoroutineScope(Dispatchers.IO).launch {
            val tasksDueToday = repository.getTasksDueToday().first()
            val overdueTasksCount = repository.getOverdueTasks().first().size

            if (tasksDueToday.isNotEmpty() || overdueTasksCount > 0) {
                showNotification(context, tasksDueToday.size, overdueTasksCount)
            }
        }
    }

    private fun showNotification(context: Context, tasksDueToday: Int, overdueTasks: Int) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Daily Summary",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Daily summary of tasks"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("navigate_to_summary", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = buildString {
            if (overdueTasks > 0) {
                append("$overdueTasks overdue")
            }
            if (tasksDueToday > 0) {
                if (isNotEmpty()) append(", ")
                append("$tasksDueToday due today")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("Daily Summary")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "daily_summary_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
