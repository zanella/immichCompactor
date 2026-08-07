package services

import database.UserId
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs [ImmichService.findAndEnqueueAllAssets] in a virtual thread so the request thread
 * returns immediately, and exposes a pollable per-user status. At most one job runs per
 * user at a time.
 */
@Singleton
class AssetRefreshJobService(
    private val immichService: ImmichService,
) {
    enum class State { RUNNING, DONE, FAILED }

    data class JobStatus(
        val state: State,
        val assetsFound: Long = 0,
        val assetsQueued: Long = 0,
        val error: String? = null,
    )

    // userId -> latest status. One entry per user; overwritten on each new job.
    private val jobs = ConcurrentHashMap<UserId, JobStatus>()

    fun status(userId: UserId): JobStatus? = jobs[userId]

    /**
     * Launches a refresh for [userId] unless one is already running; returns true if a new
     * job was started. The RUNNING flag is checked atomically so concurrent calls can't
     * spawn duplicate jobs.
     */
    fun start(
        userId: UserId,
        apiKey: String,
        immichServerUrl: String,
    ): Boolean {
        var started = false

        jobs.compute(userId) { _, current ->
            if (current?.state == State.RUNNING) {
                current
            } else {
                started = true
                JobStatus(State.RUNNING)
            }
        }

        if (started) {
            Thread.startVirtualThread { runRefresh(userId, apiKey, immichServerUrl) }
        }

        return started
    }

    private fun runRefresh(
        userId: UserId,
        apiKey: String,
        immichServerUrl: String,
    ) {
        jobs[userId] =
            try {
                val client = immichService.instantiateClient(immichServerUrl)
                val result = immichService.findAndEnqueueAllAssets(apiKey, userId, client)
                JobStatus(State.DONE, result.assetsFound, result.assetsQueued)
            } catch (e: Throwable) {
                JobStatus(State.FAILED, error = e.message ?: e.toString())
            }
    }
}
