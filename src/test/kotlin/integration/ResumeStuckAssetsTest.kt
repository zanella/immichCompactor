package integration

import database.AssetConversionState
import internal.lang.CustomEither
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ResumeStuckAssetsTest : ImmichTestEnvironment(doCleanUp = false) {
    @BeforeEach
    fun setUp() {
        assetConversionService.underTest.quitAfterStep = true
    }

    @Test
    @Order(1)
    fun videoIsUploadedAndReplacementAsWell() {
        val firstVideoFile = getTestAsset("src/test/resources/assets/first_compressable_video.mp4")

        val originalAssetId = uploadVideoToImmich(firstVideoFile).id

        val userInfo = getUserInfo()

        immichService.findAndEnqueueAllAssets(client, userInfo)

        assetConversionService.processQueuedAsset(userInfo.id, originalAssetId).also {
            assertTrue(it is CustomEither.Right)
            assertEquals(it.value, "Under test: Leaving after step QUEUED has been completed")
        }

        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.flush()
            assetStagingAreaRepository.listAll().single().run {
                assertEquals(assetId, originalAssetId)
                assertEquals(currentState, AssetConversionState.REPLACEMENT_HAS_BEEN_UPLOADED)
            }
        }
    }

    @Test
    @Order(2)
    fun `asset in REPLACEMENT_HAS_BEEN_UPLOADED resumes to terminal state`() {
        assetConversionService
            .processQueuedAsset(getUserInfo().id, assetStagingAreaRepository.listAll().single().assetId)
            .also {
                assertTrue(it is CustomEither.Right)
                assertEquals(it.value, "Under test: Leaving after step REPLACEMENT_HAS_BEEN_UPLOADED has been completed")
            }

        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.flush()
            assetStagingAreaRepository.listAll().single().run {
                assertEquals(AssetConversionState.REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA, currentState)
            }
        }
    }

    @Test
    @Order(3)
    fun `asset in REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA resumes to terminal state`() {
        assetConversionService
            .processQueuedAsset(getUserInfo().id, assetStagingAreaRepository.listAll().single().assetId)
            .also {
                assertTrue(it is CustomEither.Right)
                assertEquals(it.value, "Under test: Leaving after step REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA has been completed")
            }

        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.flush()
            assetStagingAreaRepository.listAll().single().run {
                assertEquals(currentState, AssetConversionState.WAITING_DELETION)
            }
        }
    }

    @Test
    @Order(4)
    fun `asset in WAITING_DELETION resumes to terminal state`() {
        assetConversionService
            .processQueuedAsset(getUserInfo().id, assetStagingAreaRepository.listAll().single().assetId)
            .also {
                assertTrue(it is CustomEither.Right)
                assertEquals(it.value, "Under test: Leaving after step WAITING_DELETION has been completed")
            }

        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.flush()
            assertTrue { assetStagingAreaRepository.listAll().isEmpty() }
        }
    }

    @Test
    @Order(5)
    fun tearDown() {
        doCleanUp = true
    }
}
