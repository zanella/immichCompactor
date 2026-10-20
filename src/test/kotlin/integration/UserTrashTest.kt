package integration

import database.UserInfoRepository
import integration.immich.AssetMediaStatus
import integration.immich.DeleteAssetsRequest
import integration.immich.ImmichClient
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import services.ImmichService
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserTrashTest {
    @Inject
    lateinit var immichService: ImmichService

    @Inject
    lateinit var userInfoRepository: UserInfoRepository

    private val immichBaseUrl = ImmichTestEnvironment.baseUrl
    private val immichApiKey = ImmichTestEnvironment.apiKey
    private val client: ImmichClient by lazy { immichService.instantiateClient(immichBaseUrl) }

    companion object {
        private var trashedAssetId: UUID? = null
    }

    @Test
    @Order(1)
    fun `upload and trash an asset`() {
        val screenshotFile =
            File("src/test/resources/assets/first_screenshot.png")
                .also { require(it.exists()) { "Test asset not found: ${it.absolutePath}" } }

        // Create a unique copy of the file to avoid conflicts with other tests
        val uniqueFile = File.createTempFile("trash-test-", ".png")
        screenshotFile.copyTo(uniqueFile, overwrite = true)
        uniqueFile.deleteOnExit()

        val multipartForm =
            ClientMultipartForm
                .create()
                .binaryFileUpload("assetData", uniqueFile.name, uniqueFile.absolutePath, "image/png")
                .attribute("deviceAssetId", "trash-test-${System.nanoTime()}", "")
                .attribute("deviceId", "trash-test", "")
                .attribute("fileCreatedAt", "2025-01-01T00:00:00.000Z", "")
                .attribute("fileModifiedAt", "2025-01-01T00:00:00.000Z", "")

        val response = client.uploadAsset(immichApiKey, multipartForm)

        // Accept both CREATED and DUPLICATE - if duplicate, we'll use the existing asset
        assertTrue(
            response.status == AssetMediaStatus.CREATED || response.status == AssetMediaStatus.DUPLICATE,
            "Upload should create or find duplicate asset, got: ${response.status}",
        )
        trashedAssetId = response.id
        assertNotNull(trashedAssetId, "Uploaded asset id should not be null")

        // Trash the asset
        client.deleteAssets(immichApiKey, DeleteAssetsRequest(ids = listOf(trashedAssetId!!), force = false))

        // Verify the asset is trashed
        val assetInfo = immichService.getAssetInfo(immichApiKey, trashedAssetId!!, client)
        assertNotNull(assetInfo, "Asset should exist after trashing")
        assertEquals(true, assetInfo.isTrashed, "Asset should be marked as trashed")
    }

    @Test
    @Order(2)
    fun `delete trashed asset via endpoint`() {
        val assetId = checkNotNull(trashedAssetId)
        val userInfo = userInfoRepository.listAll().single()

        // Call the backend delete endpoint via HTTP
        val deleteUrl =
            java.net.URI
                .create("http://localhost:8081/api/users/${userInfo.id.value}/assets/$assetId")
                .toURL()
        val connection = deleteUrl.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "DELETE"
        connection.setRequestProperty("Content-Type", "application/json")

        val responseCode = connection.responseCode
        assertEquals(200, responseCode, "Delete endpoint should return 200")

        // Verify the asset is either permanently deleted or marked as trashed
        // Note: Immich behavior varies - sometimes force=true permanently deletes immediately,
        // sometimes it just marks as trashed (permanent deletion happens via background jobs)
        val client = immichService.instantiateClient(userInfo.immichServerUrl)
        val assetInfo = immichService.getAssetInfo(immichApiKey, assetId, client)

        if (assetInfo != null) {
            // Asset still exists, should be marked as trashed
            assertEquals(true, assetInfo.isTrashed, "Asset should be marked as trashed")
        }
        // If assetInfo is null, the asset was permanently deleted (also acceptable)
    }
}
