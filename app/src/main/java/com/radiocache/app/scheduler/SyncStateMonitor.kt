package com.radiocache.app.scheduler

import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for "is a sync running right now".
 * Observed from Home/Downloads to disable sync buttons mid-run, which
 * kills the duplicate-worker pile-up at the UI root (rapid re-taps while
 * a 85MB download is in flight used to stack concurrent workers).
 *
 * One-time syncs (TAG_SYNC): ENQUEUED or RUNNING counts — they're about
 * to consume bandwidth. Periodic (TAG_SYNC_PERIODIC): only RUNNING counts —
 * a pending periodic is ENQUEUED almost forever and must not read as
 * "syncing" (that stuck the Home button for the whole <=0.7.6 era).
 */
class SyncStateMonitor(workManager: WorkManager) {

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var oneTime: List<WorkInfo> = emptyList()
    private var periodic: List<WorkInfo> = emptyList()
    private var capture: List<WorkInfo> = emptyList()

    private fun recompute() {
        _syncing.value =
            oneTime.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } ||
            periodic.any { it.state == WorkInfo.State.RUNNING } ||
            capture.any { it.state == WorkInfo.State.RUNNING }
    }

    private val oneTimeObserver = Observer<List<WorkInfo>> { infos ->
        oneTime = infos
        recompute()
    }

    private val periodicObserver = Observer<List<WorkInfo>> { infos ->
        periodic = infos
        recompute()
    }

    private val captureObserver = Observer<List<WorkInfo>> { infos ->
        capture = infos
        recompute()
    }

    init {
        workManager.getWorkInfosByTagLiveData(SyncSchedulerImpl.TAG_SYNC)
            .observeForever(oneTimeObserver)
        workManager.getWorkInfosByTagLiveData(SyncSchedulerImpl.TAG_SYNC_PERIODIC)
            .observeForever(periodicObserver)
        workManager.getWorkInfosByTagLiveData(CaptureScheduler.TAG_CAPTURE)
            .observeForever(captureObserver)
    }
}
