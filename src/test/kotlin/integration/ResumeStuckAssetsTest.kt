package integration

import database.AssetConversionState
import database.AssetStagingAreaRepository
import database.StagedAsset
import database.UserInfoRepository
import integration.immich.AssetResponseDto
import integration.immich.BulkTagAssetsDto
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
import services.ContentType
import services.ImmichService
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ResumeStuckAssetsTest {
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

    @Test
    @Order(1)
    fun `asset in REPLACEMENT_HAS_BEEN_UPLOADED resumes to terminal state`() {
        val (originalId, replacementId) = uploadOriginalAndReplacement()
        val userInfo = userInfoRepository.listAll().single()

        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.deleteAll()
            assetStagingAreaRepository.persist(
                StagedAsset(
                    userId = userInfo.id,
                    assetId = originalId,
                    contentType = ContentType.IMAGE,
                    currentState = AssetConversionState.REPLACEMENT_HAS_BEEN_UPLOADED,
                    replacementId = replacementId,
                ),
            )
        }

        val result = assetConversionService.processQueuedAsset(userInfo.id, originalId)

        assertTrue(result is internal.lang.CustomEither.Left, "Expected success, got: $result")

        val stagedAssets =
            QuarkusTransaction
                .requiringNew()
                .call { assetStagingAreaRepository.listAll().filter { it.assetId == originalId } }
        assertTrue(stagedAssets.isEmpty(), "Asset should be dropped from staging area after completion")

        assertOriginalTrashedAndReplacementTagged(originalId, replacementId)
    }

    @Test
    @Order(2)
    fun `asset in REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA resumes to terminal state`() {
        val (originalId, replacementId) = uploadOriginalAndReplacement()
        val userInfo = userInfoRepository.listAll().single()

        immichService.transferMetadata(
            immichApiKey,
            client,
            originalId,
            replacementId,
        )

        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.deleteAll()
            assetStagingAreaRepository.persist(
                StagedAsset(
                    userId = userInfo.id,
                    assetId = originalId,
                    contentType = ContentType.IMAGE,
                    currentState = AssetConversionState.REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA,
                    replacementId = replacementId,
                ),
            )
        }

        val result = assetConversionService.processQueuedAsset(userInfo.id, originalId)

        assertTrue(result is internal.lang.CustomEither.Left, "Expected success, got: $result")

        val stagedAssets =
            QuarkusTransaction
                .requiringNew()
                .call { assetStagingAreaRepository.listAll().filter { it.assetId == originalId } }
        assertTrue(stagedAssets.isEmpty(), "Asset should be dropped from staging area after completion")

        assertOriginalTrashedAndReplacementTagged(originalId, replacementId)
    }

    @Test
    @Order(3)
    fun `asset in WAITING_DELETION resumes to terminal state`() {
        val (originalId, replacementId) = uploadOriginalAndReplacement()
        val userInfo = userInfoRepository.listAll().single()

        immichService.transferMetadata(immichApiKey, client, originalId, replacementId)

        client.bulkTagAssets(
            immichApiKey,
            BulkTagAssetsDto(
                assetIds = listOf(replacementId),
                tagIds = listOf(userInfo.tagId),
            ),
        )

        QuarkusTransaction.requiringNew().run {
            assetStagingAreaRepository.deleteAll()
            assetStagingAreaRepository.persist(
                StagedAsset(
                    userId = userInfo.id,
                    assetId = originalId,
                    contentType = ContentType.IMAGE,
                    currentState = AssetConversionState.WAITING_DELETION,
                    replacementId = replacementId,
                ),
            )
        }

        val result = assetConversionService.processQueuedAsset(userInfo.id, originalId)

        assertTrue(result is internal.lang.CustomEither.Left, "Expected success, got: $result")

        val stagedAssets =
            QuarkusTransaction
                .requiringNew()
                .call { assetStagingAreaRepository.listAll().filter { it.assetId == originalId } }
        assertTrue(stagedAssets.isEmpty(), "Asset should be dropped from staging area after completion")

        assertOriginalTrashedAndReplacementTagged(originalId, replacementId)
    }

    private fun uploadOriginalAndReplacement(): Pair<UUID, UUID> {
        val screenshotFile =
            File("src/test/resources/assets/first_screenshot.png")
                .also { require(it.exists()) { "Test asset not found: ${it.absolutePath}" } }

        val uniqueBytes = UUID.randomUUID().toString().toByteArray()

        val originalFile =
            File
                .createTempFile("original-", ".png")
                .also {
                    it.writeBytes(screenshotFile.readBytes())
                    it.appendBytes(uniqueBytes)
                    it.deleteOnExit()
                }

        val originalForm =
            ClientMultipartForm
                .create()
                .binaryFileUpload("assetData", originalFile.name, originalFile.absolutePath, "image/png")
                .attribute("deviceAssetId", "original-${System.nanoTime()}", "")
                .attribute("deviceId", "resume-test", "")
                .attribute("fileCreatedAt", "2025-01-01T00:00:00.000Z", "")
                .attribute("fileModifiedAt", "2025-01-01T00:00:00.000Z", "")

        val originalResponse = client.uploadAsset(immichApiKey, originalForm)
        val originalId = originalResponse.id

        val originalInfo = client.getAssetInfo(immichApiKey, originalId).readEntity(AssetResponseDto::class.java)

        val replacementFile =
            File
                .createTempFile("replacement-", ".png")
                .also {
                    it.writeBytes(screenshotFile.readBytes())
                    it.appendBytes(uniqueBytes)
                    it.appendBytes(byteArrayOf(0x00))
                    it.deleteOnExit()
                }

        val replacementForm =
            ClientMultipartForm
                .create()
                .binaryFileUpload("assetData", replacementFile.name, replacementFile.absolutePath, "image/png")
                .attribute("deviceAssetId", "replacement-${System.nanoTime()}", "")
                .attribute("deviceId", "resume-test", "")
                .attribute("fileCreatedAt", originalInfo.fileCreatedAt, "")
                .attribute("fileModifiedAt", originalInfo.fileModifiedAt, "")

        val replacementResponse = client.uploadAsset(immichApiKey, replacementForm)
        val replacementId = replacementResponse.id

        assertNotNull(originalId)
        assertNotNull(replacementId)

        return originalId to replacementId
    }

    private fun assertOriginalTrashedAndReplacementTagged(
        originalId: UUID,
        replacementId: UUID,
    ) {
        val originalResponse = client.getAssetInfo(immichApiKey, originalId)
        assertEquals(200, originalResponse.status, "Could not fetch original asset")
        val originalAsset = originalResponse.readEntity(AssetResponseDto::class.java)
        assertTrue(originalAsset.isTrashed, "Original asset should be trashed")

        val newResponse = client.getAssetInfo(immichApiKey, replacementId)
        assertEquals(200, newResponse.status, "Could not fetch replacement asset")
        val newAsset = newResponse.readEntity(AssetResponseDto::class.java)

        val tagNames = newAsset.tags.map { it.name }
        assertTrue(
            tagNames.contains(integration.immich.IMMICH_COMPACTOR_TAG_NAME),
            "Replacement should be tagged, got tags: $tagNames",
        )
    }
}
