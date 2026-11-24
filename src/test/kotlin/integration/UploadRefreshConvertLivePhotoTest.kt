package integration

import integration.immich.AssetResponseDto
import integration.immich.IMMICH_COMPACTOR_TAG_NAME
import integration.immich.UpdateAssetDto
import internal.lang.CustomEither
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@QuarkusTest
class UploadRefreshConvertLivePhotoTest : ImmichTestEnvironment() {
    private val imageFile: File by lazy {
        getTestAsset("src/test/resources/assets/first_screenshot.png")
    }

    private val videoFile: File by lazy {
        getTestAsset("src/test/resources/assets/first_compressable_video.mp4")
    }

    @Test
    fun `live photo image and its video are both replaced`() {
        // 1. Upload the image and the video as two separate assets
        val imageId = uploadImageToImmich(imageFile).id
        val videoId = uploadVideoToImmich(videoFile).id

        // 2. Attach the video as the live photo motion part of the image
        val attached = client.updateAsset(apiKey, imageId, UpdateAssetDto(livePhotoVideoId = videoId))
        assertEquals(videoId, attached.livePhotoVideoId, "Immich did not attach the live photo video to the image")

        // 3. Refresh: both the image and its video get staged
        val userInfo = getUserInfo()
        val refresh = immichService.findAndEnqueueAllAssets(client, userInfo)
        assertEquals(2L, refresh.assetsQueued, "Expected the image and its live photo video to be queued, got: $refresh")

        // 4. Process the image — this must also process the attached video
        val result = assetConversionService.processQueuedAsset(userInfo.id, imageId)
        assertTrue(result is CustomEither.Left, "Expected live photo processing to succeed, got: $result")

        val replacementImageId = result.value.second
        assertNotEquals(imageId, replacementImageId)

        // 5. Both originals end up trashed
        val originalImage = client.getAssetInfo(apiKey, imageId).readEntity(AssetResponseDto::class.java)
        val originalVideo = client.getAssetInfo(apiKey, videoId).readEntity(AssetResponseDto::class.java)
        assertTrue(originalImage.isTrashed, "Original image should be trashed")
        assertTrue(originalVideo.isTrashed, "Original live photo video should be trashed")

        // 6. The replacement image is tagged and points at the replacement video
        val replacementImage = client.getAssetInfo(apiKey, replacementImageId).readEntity(AssetResponseDto::class.java)
        assertFalse(replacementImage.isTrashed, "Replacement image should not be trashed")
        assertTrue(
            replacementImage.tags.any { it.name == IMMICH_COMPACTOR_TAG_NAME },
            "Replacement image should be tagged with $IMMICH_COMPACTOR_TAG_NAME, got: ${replacementImage.tags.map { it.name }}",
        )
        val replacementVideoId =
            requireNotNull(replacementImage.livePhotoVideoId) {
                "Replacement image should point to the replacement live photo video"
            }
        assertNotEquals(videoId, replacementVideoId, "Replacement image should not point to the original video")

        // 7. The replacement video exists, is not trashed and is tagged
        val replacementVideo = client.getAssetInfo(apiKey, replacementVideoId).readEntity(AssetResponseDto::class.java)
        assertFalse(replacementVideo.isTrashed, "Replacement video should not be trashed")
        assertEquals("video/mp4", replacementVideo.originalMimeType)
        assertTrue(
            replacementVideo.tags.any { it.name == IMMICH_COMPACTOR_TAG_NAME },
            "Replacement video should be tagged with $IMMICH_COMPACTOR_TAG_NAME, got: ${replacementVideo.tags.map { it.name }}",
        )

        // 8. Nothing is left staged
        assertTrue(
            assetStagingAreaRepository.listAll().isEmpty(),
            "Staging area should be empty after both assets were processed",
        )
    }
}
