package dev.telemetryshield

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Refreshes the downloaded block lists about once a week in the background. */
class ListUpdateJob : JobService() {

    companion object {
        private const val JOB_ID = 4201
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        private const val FLEX_MS = 24L * 60 * 60 * 1000

        /** Schedules (or cancels) the weekly update to match the user's setting. Safe to call often. */
        fun sync(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (!Blocklists.autoUpdate(ctx)) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, ListUpdateJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setPersisted(true)
                    .setPeriodic(WEEK_MS, FLEX_MS)
                    .build()
            )
        }
    }

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            var ok = false
            try {
                Blocklists.download(applicationContext)
                ok = true
            } catch (_: Exception) {
            }
            jobFinished(params, !ok)
        }.also { it.start() }
        return true // work continues on the thread
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true // retry later
    }
}
