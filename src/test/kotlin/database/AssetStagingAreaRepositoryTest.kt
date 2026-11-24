package database

import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import services.ContentType
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Covers [AssetStagingAreaRepository.findQueuedByUserId], in particular the rule that a
 * live photo's video part — referenced by another staged asset's live_photo_video_id —
 * must never show up as a standalone queued asset, since it is only ever processed
 * together with its parent image.
 */
@QuarkusTest
class AssetStagingAreaRepositoryTest {
    @Inject
    lateinit var assetStagingAreaRepository: AssetStagingAreaRepository

    @Inject
    lateinit var userInfoRepository: UserInfoRepository

    private val createdUserIds = mutableListOf<Int>()
    private val createdAssetIds = mutableListOf<UUID>()

    @AfterEach
    fun cleanUp() {
        QuarkusTransaction.requiringNew().run {
            createdAssetIds.forEach(assetStagingAreaRepository::deleteById)
            createdUserIds.forEach(userInfoRepository::deleteById)
        }
        createdAssetIds.clear()
        createdUserIds.clear()
    }

    @Test
    fun `live photo video is excluded from queued assets`() {
        val userId = createUser()

        val livePhotoImageId = UUID.randomUUID()
        val livePhotoVideoId = UUID.randomUUID()
        val standaloneVideoId = UUID.randomUUID()

        stageAsset(userId, livePhotoImageId, ContentType.IMAGE, livePhotoVideoId = livePhotoVideoId)
        stageAsset(userId, livePhotoVideoId, ContentType.VIDEO)
        stageAsset(userId, standaloneVideoId, ContentType.VIDEO)

        val allQueued = assetStagingAreaRepository.findQueuedByUserId(userId, 0, 10, emptyList())

        assertEquals(
            setOf(livePhotoImageId, standaloneVideoId),
            allQueued.map { it.assetId }.toSet(),
            "The live photo video must not be queued standalone: it is processed with its parent image",
        )

        val videosOnly = assetStagingAreaRepository.findQueuedByUserId(userId, 0, 10, listOf(ContentType.VIDEO))

        assertEquals(
            listOf(standaloneVideoId),
            videosOnly.map { it.assetId },
            "Filtering by VIDEO must still exclude the live photo video",
        )

        assertEquals(
            2L,
            assetStagingAreaRepository.countQueuedByUserId(userId, emptyList()),
            "Count must exclude the live photo video, same as the list",
        )
        assertEquals(
            1L,
            assetStagingAreaRepository.countQueuedByUserId(userId, listOf(ContentType.VIDEO)),
            "VIDEO count must exclude the live photo video too",
        )
    }

    @Test
    fun `queued assets are scoped to the given user`() {
        val userA = createUser()
        val userB = createUser()

        val assetA = UUID.randomUUID()
        val assetB = UUID.randomUUID()
        stageAsset(userA, assetA, ContentType.VIDEO)
        stageAsset(userB, assetB, ContentType.VIDEO)

        assertEquals(
            listOf(assetA),
            assetStagingAreaRepository.findQueuedByUserId(userA, 0, 10, emptyList()).map { it.assetId },
        )
        assertEquals(
            listOf(assetB),
            assetStagingAreaRepository.findQueuedByUserId(userB, 0, 10, emptyList()).map { it.assetId },
        )
    }

    private fun createUser(): UserId =
        QuarkusTransaction.requiringNew().call {
            val user =
                UserInfo(
                    apiKey = "test-api-key",
                    name = "AssetStagingAreaRepositoryTest",
                    immichServerUrl = "http://localhost",
                    _tagUUID = UUID.randomUUID().toString(),
                )
            userInfoRepository.persist(user)
            userInfoRepository.flush()
            user.id.also { createdUserIds.add(it.value) }
        }

    private fun stageAsset(
        userId: UserId,
        assetId: UUID,
        contentType: ContentType,
        livePhotoVideoId: UUID? = null,
    ) {
        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.persist(
                StagedAsset(
                    userId = userId,
                    assetId = assetId,
                    contentType = contentType,
                    currentState = AssetConversionState.QUEUED,
                    livePhotoVideoId = livePhotoVideoId,
                ),
            )
            createdAssetIds.add(assetId)
        }
    }
}
