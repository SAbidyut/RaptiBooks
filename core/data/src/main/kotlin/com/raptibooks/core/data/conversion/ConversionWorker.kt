package com.raptibooks.core.data.conversion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.raptibooks.core.data.AppContainerProvider
import com.raptibooks.core.data.R
import com.raptibooks.core.model.BookStatus
import com.raptibooks.core.pdf.PdfOpenException
import com.raptibooks.core.pdf.PdfPasswordException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import kotlin.math.abs

/** Runs a book conversion in the background as a foreground job with a progress notification. */
class ConversionWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as AppContainerProvider).container
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        if (bookId < 0) return Result.failure()
        val books = container.bookDao
        val book = books.get(bookId) ?: return Result.failure()
        if (book.status == BookStatus.CANCELLED || book.status == BookStatus.READY || book.status == BookStatus.ORIGINAL_ONLY) return Result.success()

        val notificationId = NOTIFICATION_BASE + bookId.toInt()
        ensureChannel(applicationContext)
        tryForeground(foregroundInfo(notificationId, book.title, 0f, null))

        var lastFraction = -1f
        var lastMessage: String? = null
        var lastNotified = -1f
        return try {
            container.pipeline.convert(bookId) { p ->
                if (abs(p.fraction - lastFraction) >= 0.004f || p.message != lastMessage) {
                    lastFraction = p.fraction
                    lastMessage = p.message
                    books.updateProgress(bookId, p.fraction, p.message)
                    setProgress(workDataOf(KEY_PROGRESS to p.fraction))
                }
                if (p.fraction - lastNotified >= 0.02f) {
                    lastNotified = p.fraction
                    tryForeground(foregroundInfo(notificationId, book.title, p.fraction, p.message))
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            books.updateStatus(bookId, BookStatus.FAILED, userMessage(applicationContext, e))
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(NOTIFICATION_BASE, applicationContext.getString(R.string.notification_converting_generic), 0f, null)

    private suspend fun tryForeground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: Exception) {
            // Started from the background on Android 12+: continue as a normal job.
        }
    }

    private fun foregroundInfo(id: Int, title: String, fraction: Float, message: String?): ForegroundInfo {
        val ctx = applicationContext
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(getId())
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(ctx.getString(R.string.notification_converting, title))
            .setContentText(message ?: ctx.getString(R.string.progress_opening))
            .setProgress(1000, (fraction * 1000).toInt(), fraction <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, ctx.getString(R.string.action_cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_PROGRESS = "progress"
        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_BASE = 1000

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_conversion), NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        fun userMessage(context: Context, e: Throwable): String = when (e) {
            is ConversionException -> e.message ?: context.getString(R.string.error_generic)
            is PdfPasswordException -> context.getString(R.string.error_password)
            is PdfOpenException -> context.getString(R.string.error_damaged)
            is OutOfMemoryError -> context.getString(R.string.error_memory)
            is IOException -> if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true) {
                context.getString(R.string.error_storage)
            } else {
                context.getString(R.string.error_io, e.message ?: "")
            }
            else -> context.getString(R.string.error_with_reason, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
        }
    }
}
