package integration

import database.AssetConversionState
import database.AssetStagingAreaRepository
import database.UserInfoRepository
import integration.immich.AssetMediaStatus
import integration.immich.AssetResponseDto
import integration.immich.ImmichClient
import internal.lang.CustomEither
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import services.AssetConversionService
import services.ContentType
import services.ImmichService
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UploadRefreshConvertNonCompressibleVideoTest {
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
    }

    @Test
    @Order(1)
    fun `upload non-compressible video to Immich`() {
        val videoFile =
            File("src/test/resources/assets/first_non_compressable_video.mp4")
                .also { require(it.exists()) { "Test asset not found: ${it.absolutePath}" } }

        val multipartForm =
            ClientMultipartForm
                .create()
                .binaryFileUpload("assetData", videoFile.name, videoFile.absolutePath, "video/mp4")
                .attribute("deviceAssetId", "${videoFile.name}-test", "")
                .attribute("deviceId", "immich-compactor-test", "")
                .attribute("fileCreatedAt", "2025-01-01T00:00:00.000Z", "")
                .attribute("fileModifiedAt", "2025-01-01T00:00:00.000Z", "")

        val response = client.uploadAsset(immichApiKey, multipartForm)

        assertEquals(AssetMediaStatus.CREATED, response.status, "Upload did not create asset")
        uploadedAssetId = response.id
        assertNotNull(uploadedAssetId, "Uploaded asset id should not be null")
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
    fun `assert one VIDEO entry in DB after refresh`() {
        val stagedAssets =
            assetStagingAreaRepository
                .listAll()
                .filter { it.assetId == uploadedAssetId }

        assertEquals(1, stagedAssets.size, "Expected exactly 1 staged asset for uploaded video, got: ${stagedAssets.size}")
        assertEquals(ContentType.VIDEO, stagedAssets.single().contentType, "Expected VIDEO content type")
        assertEquals(AssetConversionState.QUEUED, stagedAssets.single().currentState, "Expected QUEUED state")
    }

    @Test
    @Order(4)
    fun `convert the video and assert not improvable`() {
        val assetId = checkNotNull(uploadedAssetId) { "No uploaded asset id" }
        val userInfo = userInfoRepository.listAll().single()

        val result = assetConversionService.processQueuedAsset(userInfo.id, assetId)

        assertTrue(
            result is CustomEither.Right,
            "Expected conversion to be skipped (not improvable), got: $result",
        )
        assertTrue(
            result.value.contains("smaller"),
            "Expected 'smaller' reason, got: ${result.value}",
        )

        val stagedAsset =
            assetStagingAreaRepository
                .listAll()
                .single { it.assetId == assetId }

        assertEquals(
            AssetConversionState.KEEP_AS_IS,
            stagedAsset.currentState,
            "Expected KEEP_AS_IS state for non-improvable asset",
        )
    }

    @Test
    @Order(5)
    fun `original asset is not trashed`() {
        val originalId = checkNotNull(uploadedAssetId)

        val originalResponse = client.getAssetInfo(immichApiKey, originalId)
        assertEquals(200, originalResponse.status, "Could not fetch original asset")

        val originalAsset = originalResponse.readEntity(AssetResponseDto::class.java)
        assertFalse(originalAsset.isTrashed, "Original asset should not be trashed when not improvable")
    }
}
