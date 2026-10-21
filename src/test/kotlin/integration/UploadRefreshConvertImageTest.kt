package integration

import integration.immich.AssetMediaStatus
import integration.immich.AssetResponseDto
import integration.immich.IMMICH_COMPACTOR_TAG_NAME
import internal.lang.CustomEither
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@QuarkusTest
class UploadRefreshConvertImageTest : ImmichTestEnvironment() {
    private val firstImageFile: File by lazy {
        getTestAsset("src/test/resources/assets/first_screenshot.png")
    }

    @Test
    fun `upload screenshot to Immich`() {
        uploadImageToImmich(firstImageFile)
            .also { assertEquals(AssetMediaStatus.CREATED, it.status, "Upload did not create asset") }
    }

    @Test
    fun `refresh assets`() {
        uploadImageToImmich(firstImageFile)

        immichService.findAndEnqueueAllAssets(client, getUserInfo()).also {
            assertTrue(it.assetsFound > 0, "Expected at least 1 asset found, got: $it")
            assertTrue(it.assetsQueued > 0, "Expected at least 1 asset queued, got: $it")
        }
    }

    @Test
    fun `assert one IMAGE entry in DB after refresh`() {
        uploadImageToImmich(firstImageFile)

        immichService.findAndEnqueueAllAssets(client, getUserInfo())

        assetStagingAreaRepository.listAll().also {
            assertEquals(1, it.size, "Expected exactly 1 staged asset, got: ${it.size}")
            assertEquals(
                services.ContentType.IMAGE,
                it.first().contentType,
                "Expected IMAGE content type",
            )
            assertEquals(
                database.AssetConversionState.QUEUED,
                it.first().currentState,
                "Expected QUEUED state",
            )
        }
    }

    @Test
    fun `convert the asset`() {
        val assetId = uploadImageToImmich(firstImageFile).id

        immichService.findAndEnqueueAllAssets(client, getUserInfo())

        assetConversionService.processQueuedAsset(getUserInfo().id, assetId).also {
            assertTrue(it is CustomEither.Left, "Expected conversion to succeed, got: $it")
            assertNotNull(it.value.second, "Expected a replacement asset id")
        }
    }

    @Test
    fun `original asset is trashed and replacement is in timeline`() {
        val originalId = uploadImageToImmich(firstImageFile).id

        immichService.findAndEnqueueAllAssets(client, getUserInfo())

        val newId =
            assetConversionService
                .processQueuedAsset(getUserInfo().id, originalId)
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

    @Test
    fun `trashed asset appears in trash search results`() {
        val originalId = uploadImageToImmich(firstImageFile).id

        immichService.findAndEnqueueAllAssets(client, getUserInfo())

        assetConversionService.processQueuedAsset(getUserInfo().id, originalId)

        waitUntilAllQueuesAreEmpty()

        val trashedAssets = immichService.findTrashedAssets(client, apiKey)

        val trashedIds = trashedAssets.assets.items.map { it.id }
        assertTrue(
            trashedIds.contains(originalId),
            "Trashed original should appear in trash search results, got: $trashedIds",
        )
    }

    @Test
    fun `fetch non-existent asset returns null`() {
        immichService
            .getAssetInfo(apiKey, UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff"), client)
            .also { assertNull(it, "Expected null for non-existent asset") }
    }
}
