package com.raptibooks.core.data.conversion

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Enqueues conversions with WorkManager so they survive app restarts and process death. */
class ConversionScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    fun enqueue(bookId: Long, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ConversionWorker>()
            .setInputData(workDataOf(ConversionWorker.KEY_BOOK_ID to bookId))
            .addTag(TAG)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(name(bookId), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(bookId: Long) {
        workManager.cancelUniqueWork(name(bookId))
    }

    private fun name(bookId: Long) = "convert-$bookId"

    companion object {
        const val TAG = "conversion"
    }
}
