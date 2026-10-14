package services

import database.UserId
import database.UserInfo
import integration.immich.ImmichClient
import internal.lang.getOrElseException
import internal.lang.runCatchingSafely
import jakarta.inject.Singleton
import kotlinx.serialization.Serializable
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

    @Serializable
    data class AssetRefreshJobStatus(
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

    private val jobs = ConcurrentHashMap<UserId, AssetRefreshJobStatus>()

    fun status(userId: UserId): AssetRefreshJobStatus? = jobs[userId]

    /**
     * Launches a refresh for [UserId] unless one is already running; returns true if a new
     * job was started. The RUNNING flag is checked atomically so concurrent calls can't
     * spawn duplicate jobs.
     */
    fun start(userInfo: UserInfo): AssetRefreshJobStatus =
        perUserLimit(userInfo.id) {
            with(userInfo) {
                jobs[id]?.also { lastKnownJob ->
                    if (lastKnownJob.state == State.DONE) {
                        jobs.remove(id)
                    } else {
                        return@perUserLimit lastKnownJob
                    }
                }

                return jobs.getOrPut(id) {
                    val t = Thread.ofVirtual().unstarted { runRefresh(userInfo) }

                    AssetRefreshJobStatus(threadId = t.threadId(), State.RUNNING, 0).also { t.start() }
                }
            }
        }

    private fun runRefresh(userInfo: UserInfo): AssetRefreshJobStatus =
        jobs
            .getValue(userInfo.id)
            .let { prevJobStatus ->
                runCatchingSafely {
                    val client = ImmichClient.instantiateClient(userInfo.immichServerUrl)

                    immichService.findAndEnqueueAllAssets(client, userInfo).let {
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
            .also { jobs[userInfo.id] = it }
}
