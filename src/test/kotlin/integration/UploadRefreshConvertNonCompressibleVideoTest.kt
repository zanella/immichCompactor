package integration

import database.AssetConversionState
import integration.immich.AssetMediaStatus
import integration.immich.AssetResponseDto
import internal.lang.CustomEither
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test
import services.ContentType
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@QuarkusTest
class UploadRefreshConvertNonCompressibleVideoTest : ImmichTestEnvironment() {
    private val firstNonCompressibleVideoFile: File by lazy {
        getTestAsset("src/test/resources/assets/first_non_compressable_video.mp4")
    }

    @Test
    fun `upload non-compressible video to Immich`() {
        val response = uploadVideoToImmich(firstNonCompressibleVideoFile)

        assertEquals(AssetMediaStatus.CREATED, response.status, "Upload did not create asset")
    }

    @Test
    fun `refresh assets`() {
        uploadVideoToImmich(firstNonCompressibleVideoFile)

        immichService.findAndEnqueueAllAssets(client, getUserInfo()).also {
            assertTrue(it.assetsFound > 0, "Expected at least 1 asset found, got: $it")
            assertTrue(it.assetsQueued > 0, "Expected at least 1 asset queued, got: $it")
        }
    }

    @Test
    fun `assert one VIDEO entry in DB after refresh`() {
        uploadVideoToImmich(firstNonCompressibleVideoFile)

        immichService.findAndEnqueueAllAssets(client, getUserInfo())

        assetStagingAreaRepository.listAll().also {
            assertEquals(1, it.size, "Expected exactly 1 staged asset for uploaded video, got: ${it.size}")
            assertEquals(ContentType.VIDEO, it.single().contentType, "Expected VIDEO content type")
            assertEquals(AssetConversionState.QUEUED, it.single().currentState, "Expected QUEUED state")
        }
    }

    @Test
    fun `convert the video and assert not improvable`() {
        val assetId = uploadVideoToImmich(firstNonCompressibleVideoFile).id

        val userInfo = getUserInfo()

        immichService.findAndEnqueueAllAssets(client, userInfo)

        assetConversionService.processQueuedAsset(userInfo.id, assetId).also {
            assertTrue(
                it is CustomEither.Right,
                "Expected conversion to be skipped (not improvable), got: $it",
            )

            assertTrue(it.value.contains("smaller"), "Expected 'smaller' reason, got: ${it.value}")
        }

        assetStagingAreaRepository.listAll().single { it.assetId == assetId }.also {
            assertEquals(
                AssetConversionState.KEEP_AS_IS,
                it.currentState,
                "Expected KEEP_AS_IS state for non-improvable asset",
            )
        }

        // Assert: original asset is not trashed
        val originalResponse = client.getAssetInfo(apiKey, assetId)
        assertEquals(200, originalResponse.status, "Could not fetch original asset")

        val originalAsset = originalResponse.readEntity(AssetResponseDto::class.java)
        assertFalse(originalAsset.isTrashed, "Original asset should not be trashed when not improvable")
    }

    @Test
    fun `KEEP_AS_IS asset is not re-enqueued on second refresh`() {
        val assetId = uploadVideoToImmich(firstNonCompressibleVideoFile).id

        val userInfo = getUserInfo()

        immichService.findAndEnqueueAllAssets(client, userInfo)

        assetConversionService.processQueuedAsset(userInfo.id, assetId)

        // 2nd run
        immichService
            .findAndEnqueueAllAssets(client, userInfo)
            .also { assertEquals(0L, it.assetsQueued, "KEEP_AS_IS asset should not be re-enqueued, got: $it") }

        assetStagingAreaRepository.listAll().filter { it.assetId == assetId }.also {
            assertEquals(1, it.size, "Expected exactly 1 staged asset, got: ${it.size}")
            assertEquals(
                AssetConversionState.KEEP_AS_IS,
                it.single().currentState,
                "Asset should still be in KEEP_AS_IS state",
            )
        }
    }
}
