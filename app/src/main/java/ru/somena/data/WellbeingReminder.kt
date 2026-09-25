package ru.somena.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.coroutineScope
import ru.somena.R

/**
 * Вечернее напоминание Самочувствия (тикет 05): ежедневно в 21:00 проверяем,
 * отмечен ли день; если нет — уведомление. Отключается в настройках (prefs).
 */
object WellbeingReminder {

    private const val WORK_NAME = "wellbeing_reminder"
    private const val CHANNEL_ID = "wellbeing"
    private const val NOTIFICATION_ID = 1
    const val REMIND_TIME = 21 // часов

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("somena", Context.MODE_PRIVATE)
            .getBoolean("reminder_enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("somena", Context.MODE_PRIVATE)
            .edit().putBoolean("reminder_enabled", enabled).apply()
        schedule(context, enabled)
    }

    /** Идемпотентно; вызывается при старте приложения и при переключении. */
    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = LocalTime.now()
        var delay = Duration.between(now, LocalTime.of(REMIND_TIME, 0))
        if (delay.isNegative || delay.isZero) delay = delay.plusDays(1)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, java.util.concurrent.TimeUnit.DAYS)
            .setInitialDelay(delay)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID, "Самочувствие", NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }
}

class ReminderWorker(private val appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = coroutineScope {
        val db = SliceDb(appContext)
        if (db.getWellbeing(LocalDate.now()) != null) {
            return@coroutineScope Result.success() // день уже отмечен — молчим
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@coroutineScope Result.success() // нет разрешения — молчим
        }
        WellbeingReminder.ensureChannel(appContext)
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val pending = PendingIntent.getActivity(
            appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = androidx.core.app.NotificationCompat.Builder(appContext, "wellbeing")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Как самочувствие?")
            .setContentText("Отметь энергию, настроение и сон — займёт секунд десять.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(appContext).notify(1, notification)
        Result.success()
    }
}
