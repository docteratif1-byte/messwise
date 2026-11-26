package com.kodawick.messwise.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kodawick.messwise.MainActivity
import com.kodawick.messwise.R
import com.kodawick.messwise.data.Graph
import java.util.concurrent.TimeUnit

/**
 * Free notifications without a push server: every hour the phone checks for new in-app notifications
 * (bills, payment reminders, notices, slips) and shows them as phone notifications.
 */
object Notify {
    const val CH_BILLS = "bills"
    const val CH_UPDATES = "updates"
    private const val WORK = "messwise_notify"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_BILLS, ctx.getString(R.string.ch_bills), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, ctx.getString(R.string.ch_updates), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<NotifyWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(WORK)

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, id: Int, channel: String, title: String, body: String, route: String?) {
        if (!canNotify(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (route != null) intent.putExtra(MainActivity.EXTRA_ROUTE, route)
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4A2545.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }
}

class NotifyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Graph.session.signedIn || !Graph.prefs.notificationsOn) return Result.success()
        return try {
            val last = Graph.prefs.lastNotifiedId
            val fresh = Graph.repo.notifications().filter { !it.read && it.id > last }.sortedBy { it.id }
            // The first run only remembers where we are, so old notices do not all pop up at once.
            if (last > 0) fresh.takeLast(6).forEach { n ->
                val channel = if (n.kind in setOf("bill", "reminder", "slip", "paid", "sent_back")) Notify.CH_BILLS else Notify.CH_UPDATES
                Notify.show(ctx, (n.id % Int.MAX_VALUE).toInt(), channel, n.title, n.body, n.placeId?.let { "place/$it" })
            }
            fresh.maxOfOrNull { it.id }?.let { Graph.prefs.lastNotifiedId = it } ?: run { if (last == 0L) Graph.prefs.lastNotifiedId = 1 }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
