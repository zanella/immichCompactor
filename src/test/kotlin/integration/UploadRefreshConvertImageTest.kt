package integration

import database.AssetStagingAreaRepository
import database.UserInfoRepository
import integration.immich.AssetMediaStatus
import integration.immich.AssetResponseDto
import integration.immich.IMMICH_COMPACTOR_TAG_NAME
import integration.immich.ImmichClient
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import services.AssetConversionService
import services.ImmichService
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UploadRefreshConvertImageTest {
    @Inject
    lateinit var immichService: ImmichService

    @Inject
    lateinit var userInfoRepository: UserInfoRepository

    @Inject
    lateinit var assetStagingAreaRepository: AssetStagingAreaRepository

    @Inject
    lateinit var assetConversionService: AssetConversionService

    private val immichBaseUrl = ImmichTestEnvironment.baseUrl
    private val immichApiKey = ImmichTestEnvironment.apiKey
    private val client: ImmichClient by lazy { immichService.instantiateClient(immichBaseUrl) }

    companion object {
        private var uploadedAssetId: UUID? = null
        private var replacementAssetId: UUID? = null
        private var originalAssetInfo: AssetResponseDto? = null
    }

    @Test
    @Order(1)
    fun `upload screenshot to Immich`() {
        val screenshotFile =
            File("src/test/resources/assets/first_screenshot.png")
                .also { require(it.exists()) { "Test asset not found: ${it.absolutePath}" } }

        val multipartForm =
            ClientMultipartForm
                .create()
                .binaryFileUpload("assetData", screenshotFile.name, screenshotFile.absolutePath, "image/png")
                .attribute("deviceAssetId", "${screenshotFile.name}-test", "")
                .attribute("deviceId", "immich-compactor-test", "")
                .attribute("fileCreatedAt", "2025-01-01T00:00:00.000Z", "")
                .attribute("fileModifiedAt", "2025-01-01T00:00:00.000Z", "")

        val response = client.uploadAsset(immichApiKey, multipartForm)

        assertEquals(AssetMediaStatus.CREATED, response.status, "Upload did not create asset")
        uploadedAssetId = response.id
        assertNotNull(uploadedAssetId, "Uploaded asset id should not be null")

        val assetInfoResponse = client.getAssetInfo(immichApiKey, uploadedAssetId!!)
        originalAssetInfo = assetInfoResponse.readEntity(AssetResponseDto::class.java)
    }

    @Test
    @Order(2)
    fun `refresh assets`() {
        QuarkusTransaction.requiringNew().run { assetStagingAreaRepository.deleteAll() }

        val userInfo =
            QuarkusTransaction
                .requiringNew()
                .call { userInfoRepository.listAll().single() }

        val result = immichService.findAndEnqueueAllAssets(client, userInfo)

        assertTrue(result.assetsFound > 0, "Expected at least 1 asset found, got: $result")
        assertTrue(result.assetsQueued > 0, "Expected at least 1 asset queued, got: $result")
    }

    @Test
    @Order(3)
    fun `assert one IMAGE entry in DB after refresh`() {
        val stagedAssets = assetStagingAreaRepository.listAll()

        assertEquals(1, stagedAssets.size, "Expected exactly 1 staged asset, got: ${stagedAssets.size}")
        assertEquals(
            services.ContentType.IMAGE,
            stagedAssets.first().contentType,
            "Expected IMAGE content type",
        )
        assertEquals(
            database.AssetConversionState.QUEUED,
            stagedAssets.first().currentState,
            "Expected QUEUED state",
        )
    }

    @Test
    @Order(4)
    fun `convert the asset`() {
        val assetId = checkNotNull(uploadedAssetId) { "No uploaded asset id" }
        val userInfo = userInfoRepository.listAll().single()

        val result = assetConversionService.processQueuedAsset(userInfo.id, assetId)

        assertTrue(
            result is internal.lang.CustomEither.Left,
            "Expected conversion to succeed, got: $result",
        )

        assertNotNull(result.value.second, "Expected a replacement asset id")
    }

    @Test
    @Order(5)
    fun `original asset is trashed and replacement is in timeline`() {
        val originalId = checkNotNull(uploadedAssetId)
        val newId = checkNotNull(replacementAssetId)
        val original = checkNotNull(originalAssetInfo)

        val originalResponse = client.getAssetInfo(immichApiKey, originalId)
        assertEquals(200, originalResponse.status, "Could not fetch original asset")
        val originalAsset = originalResponse.readEntity(AssetResponseDto::class.java)
        assertTrue(originalAsset.isTrashed, "Original asset should be trashed")

        val newResponse = client.getAssetInfo(immichApiKey, newId)
        assertEquals(200, newResponse.status, "Could not fetch replacement asset")
        val newAsset = newResponse.readEntity(AssetResponseDto::class.java)
        assertFalse(newAsset.isTrashed, "Replacement asset should not be trashed")

        assertEquals(
            original.fileModifiedAt,
            newAsset.fileModifiedAt,
            "Replacement should have same fileModifiedAt as original",
        )
        assertEquals(
            original.isFavorite,
            newAsset.isFavorite,
            "Replacement should have same isFavorite as original",
        )
        assertEquals(
            original.isArchived,
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
    @Order(6)
    fun `trashed asset appears in trash search results`() {
        val originalId = checkNotNull(uploadedAssetId)
        val userInfo = userInfoRepository.listAll().single()

        val trashedAssets = immichService.findTrashedAssets(client, userInfo)

        val trashedIds = trashedAssets.assets.items.map { it.id }
        assertTrue(
            trashedIds.contains(originalId),
            "Trashed original should appear in trash search results, got: $trashedIds",
        )
    }

    @Test
    @Order(7)
    fun `fetch non-existent asset returns null`() {
        val bogusId = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff")

        val result = immichService.getAssetInfo(immichApiKey, bogusId, client)

        assertNull(result, "Expected null for non-existent asset")
    }
}
