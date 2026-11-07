package com.shadowbox.x.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shadowbox.x.ShadowBoxApp
import java.util.concurrent.TimeUnit

/**
 * The VMK exists only in RAM while the vault is unlocked, so a backup can only be written then.
 * If the vault is locked when the schedule fires the job just flags "backup due"; the app runs it right after the next unlock.
 */
class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ShadowBoxApp).container
        if (!c.vault.isUnlocked) {
            c.settings.update { it.copy(backupDue = true) }
            return Result.success()
        }
        try { c.backup.runScheduled() } catch (e: Exception) {
            c.backup.markResult(false, e.message ?: "error")
            c.settings.update { it.copy(backupDue = true) }
        }
        return Result.success()
    }
}

object BackupScheduler {
    private const val PERIODIC = "sbx_backup_schedule"
    fun apply(ctx: Context, days: Int) {
        val wm = WorkManager.getInstance(ctx)
        if (days <= 0) { wm.cancelUniqueWork(PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<BackupWorker>(days.toLong(), TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("sbx_backup_now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<BackupWorker>().build())
    }
}
