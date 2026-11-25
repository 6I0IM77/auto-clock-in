package com.shuangka.punch.core

import android.app.job.JobParameters
import android.app.job.JobService

/** JobScheduler 周期自检：系统把整机排程交给它，重启后依然会被调起来 */
class KeepAliveJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        KeepAlive.checkAndReschedule(this)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
