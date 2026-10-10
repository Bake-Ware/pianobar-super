package org.pianobarsuper.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** The periodic check, about every six hours while the phone has a network. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Updater.check(userAsked = false)
        return Result.success()
    }
}
