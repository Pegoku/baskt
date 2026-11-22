package nl.baskt.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.baskt.BasktApp
import nl.baskt.MainActivity
import nl.baskt.R

/**
 * The server activates Bonus Box offers that match the basket on its own (AH can't undo one), so the app
 * checks for new auto-activations and says so in a notification.
 */
class AutoActivationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as BasktApp).container
        val settings = withContext(Dispatchers.Main.immediate) { container.awaitSettings() }
        if (settings.token.isBlank()) return Result.success()
        val offers = runCatching { container.api.ahOffers(force = false) }.getOrElse { return Result.success() }
        if (!offers.connected) return Result.success()
        val activated = offers.offers.filter { it.autoActivated && it.status == "ACTIVATED" }
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = prefs.getStringSet(KEY_SEEN, null)
        val fresh = if (known == null) emptyList() else activated.filter { it.id !in known }
        // The first check only learns what was already activated, so an update doesn't announce old offers.
        prefs.edit().putStringSet(KEY_SEEN, ((known ?: emptySet()) + activated.map { it.id }).toList().takeLast(200).toSet()).apply()
        for (offer in fresh) notify(offer)
        return Result.success()
    }

    private fun notify(offer: PersonalOffer) {
        val context = applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Bonus Box activations", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When baskt activates a Bonus Box offer that matches your basket"
        })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val deal = listOfNotNull(offer.dealText, offer.autoActivatedFor?.let { "for “$it” on your list" }).joinToString(", ")
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_bonus)
            .setContentTitle("Bonus Box: ${offer.title}")
            .setContentText(deal.ifBlank { "Activated for your basket" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify("bonus-${offer.id}", 0, notification) }
    }

    companion object {
        private const val CHANNEL = "bonus-box"
        private const val PREFS = "baskt-auto-activation"
        private const val KEY_SEEN = "seen"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Hourly, like the server's own auto-activation run. */
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<AutoActivationWorker>(1, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("baskt-auto-activation", ExistingPeriodicWorkPolicy.KEEP, work)
        }

        /** Soon after leaving the app: basket edits made in it can trigger an activation right away. */
        fun checkSoon(context: Context) {
            val work = OneTimeWorkRequestBuilder<AutoActivationWorker>().setInitialDelay(30, TimeUnit.SECONDS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork("baskt-auto-activation-soon", ExistingWorkPolicy.REPLACE, work)
        }
    }
}
