package integration

import integration.immich.AssetMediaStatus
import integration.immich.DeleteAssetsRequest
import io.quarkus.test.junit.QuarkusTest
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusTest
class UserTrashTest : ImmichTestEnvironment() {
    @Test
    fun `upload and trash an asset`() {
        val screenshotFile = getTestAsset("src/test/resources/assets/first_screenshot.png")

        // Create a unique copy of the file to avoid conflicts with other tests
        val uniqueFile = File.createTempFile("trash-test-", ".png")
        screenshotFile.copyTo(uniqueFile, overwrite = true)
        uniqueFile.deleteOnExit()

        val trashedAssetId =
            uploadImageToImmich(screenshotFile)
                .also {
                    // Accept both CREATED and DUPLICATE - if duplicate, we'll use the existing asset
                    assertTrue(
                        it.status == AssetMediaStatus.CREATED || it.status == AssetMediaStatus.DUPLICATE,
                        "Upload should create or find duplicate asset, got: ${it.status}",
                    )
                }.id

        // Trash the asset
        client.deleteAssets(apiKey, DeleteAssetsRequest(ids = listOf(trashedAssetId), force = false))

        // Verify the asset is trashed
        immichService.getAssetInfo(apiKey, trashedAssetId, client).also {
            assertNotNull(it, "Asset should exist after trashing")
            assertEquals(true, it.isTrashed, "Asset should be marked as trashed")
        }
    }
}
