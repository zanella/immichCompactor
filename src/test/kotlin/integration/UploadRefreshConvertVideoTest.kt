package integration

import database.AssetConversionState
import integration.immich.AssetMediaStatus
import integration.immich.AssetResponseDto
import integration.immich.IMMICH_COMPACTOR_TAG_NAME
import internal.lang.CustomEither
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test
import services.ContentType
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusTest
class UploadRefreshConvertVideoTest : ImmichTestEnvironment() {
    private val firstVideoFile: File by lazy {
        getTestAsset("src/test/resources/assets/first_compressable_video.mp4")
    }

    @Test
    fun `upload video to Immich`() {
        assertEquals(
            AssetMediaStatus.CREATED,
            uploadVideoToImmich(firstVideoFile).status,
            "Upload did not create asset",
        )
    }

    @Test
    fun `refresh assets`() {
        uploadVideoToImmich(firstVideoFile)

        immichService.findAndEnqueueAllAssets(client, getUserInfo()).also {
            assertTrue(it.assetsFound > 0, "Expected at least 1 asset found, got: $it")
            assertTrue(it.assetsQueued > 0, "Expected at least 1 asset queued, got: $it")
        }
    }

    @Test
    fun `assert one VIDEO entry in DB after refresh`() {
        uploadVideoToImmich(firstVideoFile)

        immichService.findAndEnqueueAllAssets(client, getUserInfo())

        assetStagingAreaRepository.listAll().also {
            assertEquals(1, it.size, "Expected exactly 1 staged asset for uploaded video, got: ${it.size}")
            assertEquals(ContentType.VIDEO, it.single().contentType, "Expected VIDEO content type")
            assertEquals(AssetConversionState.QUEUED, it.single().currentState, "Expected QUEUED state")
        }
    }

    @Test
    fun `convert the video`() {
        val assetId = uploadVideoToImmich(firstVideoFile).id

        val userInfo = getUserInfo()

        immichService.findAndEnqueueAllAssets(client, userInfo)

        assetConversionService.processQueuedAsset(userInfo.id, assetId).also {
            assertTrue(it is CustomEither.Left, "Expected video conversion to succeed, got: $it")
            assertNotNull(it.value.second, "Expected a replacement asset id")
        }
    }

    @Test
    fun `original asset is trashed and replacement is in timeline`() {
        val originalId = uploadVideoToImmich(firstVideoFile).id

        val userInfo = getUserInfo()

        immichService.findAndEnqueueAllAssets(client, userInfo)

        val newId =
            assetConversionService
                .processQueuedAsset(userInfo.id, assetStagingAreaRepository.listAll().single().assetId)
                .let {
                    assertTrue { it is CustomEither.Left }

                    (it as CustomEither.Left).value.second
                }

        val originalResponse = client.getAssetInfo(apiKey, originalId)
        assertEquals(200, originalResponse.status, "Could not fetch original asset")
        val originalAsset = originalResponse.readEntity(AssetResponseDto::class.java)
        assertTrue(originalAsset.isTrashed, "Original asset should be trashed")

        val newResponse = client.getAssetInfo(apiKey, newId)
        assertEquals(200, newResponse.status, "Could not fetch replacement asset")
        val newAsset = newResponse.readEntity(AssetResponseDto::class.java)
        assertFalse(newAsset.isTrashed, "Replacement asset should not be trashed")

        assertEquals(
            originalAsset.fileModifiedAt,
            newAsset.fileModifiedAt,
            "Replacement should have same fileModifiedAt as original",
        )
        assertEquals(
            originalAsset.isFavorite,
            newAsset.isFavorite,
            "Replacement should have same isFavorite as original",
        )
        assertEquals(
            originalAsset.isArchived,
            newAsset.isArchived,
            "Replacement should have same isArchived as original",
        )

        val tagNames = newAsset.tags.map { it.name }
        assertTrue(
            tagNames.contains(IMMICH_COMPACTOR_TAG_NAME),
            "Replacement should be tagged with $IMMICH_COMPACTOR_TAG_NAME, got tags: $tagNames",
        )
    }
}
