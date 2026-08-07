package services

import database.UserId
import internal.lang.getOrElseException
import internal.lang.runCatchingSafely
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

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
        val threadId: Long,
        val state: State,
        //
        val assetsFound: Long = 0,
        val assetsQueued: Long = 0,
        val error: String? = null,
        //
        val startedAt: Instant = Clock.System.now(),
        val endedAt: Instant? = null,
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
    ) {
        jobs[userId].also { lastKnownJob ->
            if (lastKnownJob == null) {
                return@also
            }

            if (lastKnownJob.state == State.DONE) {
                jobs.remove(userId)
            }
        }

        jobs.getOrPut(userId) {
            val t = Thread.ofVirtual().unstarted { runRefresh(userId, apiKey, immichServerUrl) }

            JobStatus(threadId = t.threadId(), State.RUNNING, 0).also { t.start() }
        }
    }

    private fun runRefresh(
        userId: UserId,
        apiKey: String,
        immichServerUrl: String,
    ) = jobs
        .getValue(userId)
        .let { prevJobStatus ->
            runCatchingSafely {
                val client = immichService.instantiateClient(immichServerUrl)

                immichService.findAndEnqueueAllAssets(apiKey, userId, client).let {
                    prevJobStatus.copy(
                        state = State.DONE,
                        assetsFound = it.assetsFound,
                        assetsQueued = it.assetsQueued,
                    )
                }
            }.getOrElseException { e: Exception ->
                prevJobStatus.copy(state = State.FAILED, error = e.message ?: e.toString())
            }
        }.copy(endedAt = Clock.System.now())
        .also { jobs[userId] = it }
}
