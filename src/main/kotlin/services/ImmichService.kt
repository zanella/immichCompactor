package services

import database.AssetConversionState
import database.AssetId
import database.AssetStagingAreaRepository
import database.StagedAsset
import database.UserInfo
import integration.immich.AssetMediaResponseDto
import integration.immich.AssetResponseDto
import integration.immich.CopyAssetRequest
import integration.immich.CreateTagRequest
import integration.immich.IMMICH_COMPACTOR_TAG_NAME
import integration.immich.ImmichClient
import integration.immich.ImmichLoggingFilter
import integration.immich.SearchAssetsRequest
import integration.immich.SearchAssetsRequest.Companion.DateFilterNullable
import integration.immich.SearchAssetsRequest.Companion.SearchFilter
import integration.immich.SearchAssetsRequest.Companion.SearchFilter.Companion.IdsFilter
import integration.immich.SearchAssetsResponse
import integration.immich.TagResponseDto
import io.quarkus.logging.Log
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm
import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@ApplicationScoped
class ImmichService(
    private val assetStagingAreaRepository: AssetStagingAreaRepository,
) {
    /**
     * I don't want to wrap the whole API, leak the client for now and decide later if it should be hidden
     */
    fun instantiateClient(
        baseUrl: String,
        isDebug: Boolean = true,
    ): ImmichClient = Companion.instantiateClient(baseUrl, isDebug)

    companion object {
        fun instantiateClient(
            baseUrl: String,
            isDebug: Boolean = true,
        ): ImmichClient =
            QuarkusRestClientBuilder
                .newBuilder()
                .baseUri(URI.create(baseUrl))
                .also {
                    if (isDebug) {
                        it.property("quarkus.rest-client.logging.scope", "request-response")
                        it.property("quarkus.rest-client.logging.body-limit", "50000")
                        it.register(ImmichLoggingFilter::class.java)
                    }
                }.build(ImmichClient::class.java)
    }

    fun getAssetInfo(
        apiKey: String,
        assetId: UUID,
        client: ImmichClient,
    ): AssetResponseDto? {
        val response =
            try {
                client.getAssetInfo(apiKey, assetId)
            } catch (e: Throwable) {
                if (e.message?.contains("400") == true || e.message?.contains("Bad Request") == true) {
                    return null
                }
                throw e
            }

        if (response.status == 400) {
            return null
        } else if (response.status != 200) {
            throw RuntimeException("Download failed with HTTP Status: ${response.status}")
        }

        return response.readEntity(AssetResponseDto::class.java)
    }

    fun findAllUntaggedAssets(
        client: ImmichClient,
        userInfo: UserInfo,
        cursor: String? = null,
    ): SearchAssetsResponse {
        val searchCriteria =
            SearchAssetsRequest(
                filter =
                    SearchFilter(
                        tagIds =
                            IdsFilter(
                                none = listOf(userInfo.tagId),
                            ),
                    ),
                cursor = cursor,
            )

        return client.searchByMetadata(userInfo.apiKey, searchCriteria)
    }

    fun findTrashedAssets(
        client: ImmichClient,
        apiKey: String,
        cursor: String? = null,
    ): SearchAssetsResponse {
        val searchCriteria =
            SearchAssetsRequest(
                filter =
                    SearchFilter(
                        trashedAt =
                            DateFilterNullable(
                                gte = "1970-01-01T00:00:00.000Z",
                            ),
                    ),
                cursor = cursor,
            )

        return client.searchByMetadata(apiKey, searchCriteria)
    }

    data class FindAndEnqueueAllAssetsResponse(
        val assetsFound: Long,
        val assetsQueued: Long,
    )

    /**
     * Walks every page of the recent-assets search. Immich returns the number of the
     * next page in [SearchAssetResponseDto.nextPage] (null once there are no more), which
     * we feed back into the following request until it runs out.
     */
    fun findAndEnqueueAllAssets(
        client: ImmichClient,
        userInfo: UserInfo,
    ): FindAndEnqueueAllAssetsResponse {
        /*
         * TODO: https://api.immich.app/endpoints/jobs/getQueuesLegacy
         *
         * It is possible, use the new queues endpoint
         *
         * The endpoint above is deprecated, so... can't be sure there are no jobs running :-/
         */

        // This runs on a bare virtual thread (no CDI request context), so DB access needs
        // explicit transactions — Panache's built-in reads only *join* a transaction,
        // they can't start one themselves.
        val skippableAssets =
            QuarkusTransaction.joiningExisting().call {
                // TODO: If the amount is huge -> think how to handle
                assetStagingAreaRepository
                    .find("currentState != ?1", AssetConversionState.WAITING_DELETION)
                    .list()
                    .map { it.assetId }
                    .toSet()
            }

        var assetsFound = 0L
        var assetsQueued = 0L
        var nextCursor: String? = null

        do {
            val searchAssetsResponse = findAllUntaggedAssets(client, userInfo, nextCursor).assets

            val assetsToBeStaged =
                searchAssetsResponse.items
                    .filter { !skippableAssets.contains(it.id) }
                    .filter { !it.isTrashed }
                    .filter { asset ->
                        val tagFound =
                            getAssetInfo(userInfo.apiKey, asset.id, client)
                                ?.tags
                                ?.filter { tag -> tag.name == IMMICH_COMPACTOR_TAG_NAME }
                                ?.toSet()
                                ?: emptySet()

                        if (!tagFound.isEmpty()) {
                            Log.debug("Asset [${asset.id}] is already tagged, skipping it")
                            false
                        } else {
                            true
                        }
                    }.map { asset ->
                        StagedAsset(
                            userId = userInfo.id,
                            assetId = asset.id,
                            contentType =
                                splitFileName(asset.originalFileName)
                                    .second
                                    .let(::getHandledContentTypeFromFileType)
                                    .contentType,
                            currentState = AssetConversionState.QUEUED,
                        )
                    }

            // Short per-page transaction, so no DB connection is held while paging through the network.
            QuarkusTransaction.requiringNew().run {
                assetStagingAreaRepository.persist(assetsToBeStaged)
            }

            assetsFound += searchAssetsResponse.items.size
            assetsQueued += assetsToBeStaged.size

            nextCursor = searchAssetsResponse.nextCursor
        } while (nextCursor != null)

        return FindAndEnqueueAllAssetsResponse(assetsFound = assetsFound, assetsQueued = assetsQueued)
    }

    fun downloadAssetToDisk(
        apiKey: String,
        assetId: UUID,
        client: ImmichClient,
        targetOutputFile: File,
    ) {
        // 1. Fire the request and obtain the network socket reference
        val response = client.assetDownloadOriginal(apiKey, assetId)

        // 2. Validate response code explicitly before touching the stream content
        if (response.status != 200) {
            throw RuntimeException("Download failed with HTTP Status: ${response.status}")
        }

        // 3. Extract the binary input stream safely
        val stream =
            response.entity as? InputStream
                ?: throw IllegalStateException("Response body does not contain an stream entity")

        stream.use { input ->
            // 4. Efficiently pipe stream blocks directly to disk (low memory usage)
            Files.copy(input, targetOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        Log.debug("Successfully wrote asset binary stream to: ${targetOutputFile.absolutePath}")
    }

    fun uploadLocalFile(
        apiKey: String,
        assetResponse: AssetResponseDto,
        client: ImmichClient,
        fileToUpload: File,
        mediaType: MediaType,
    ): AssetMediaResponseDto =
        client.uploadAsset(
            apiKey,
            ClientMultipartForm
                .create()
                .binaryFileUpload("assetData", fileToUpload.name, fileToUpload.absolutePath, mediaType.toString())
                .attribute("deviceId", "quarkus-blocking-backend", "")
                .attribute("deviceAssetId", "${fileToUpload.name}-${assetResponse.fileCreatedAt}", "")
                .attribute("fileCreatedAt", assetResponse.fileCreatedAt, "")
                .attribute("fileModifiedAt", assetResponse.fileModifiedAt, ""),
        )

    // /////////////////////////////////////////////////////////////////////////

    fun transferMetadata(
        apiKey: String,
        client: ImmichClient,
        originalAssetId: AssetId,
        replacementId: AssetId,
    ) = client.copyAsset(
        apiKey,
        CopyAssetRequest(
            albums = false,
            favorite = true,
            sharedLinks = true,
            sidecar = true,
            sourceId = originalAssetId,
            stack = true,
            targetId = replacementId,
        ),
    )

    // /////////////////////////////////////////////////////////////////////////

    fun upsertTag(
        apiKey: String,
        client: ImmichClient,
    ): TagResponseDto =
        client
            .getTags(apiKey)
            .singleOrNull { it.name == IMMICH_COMPACTOR_TAG_NAME }
            ?: client
                .createTag(
                    apiKey,
                    CreateTagRequest(name = IMMICH_COMPACTOR_TAG_NAME),
                )

    // /////////////////////////////////////////////////////////////////////////

    /* TODO: Only the admin can do this...
    fun ensureTagsFeatureEnabled(
        apiKey: String,
        client: ImmichClient,
    ) {
        val getResponse = client.getSystemConfig(apiKey)

        check(getResponse.status == 200) {
            "Failed to GET system config: ${getResponse.status}"
        }

        val currentConfig = getResponse.readEntity(String::class.java)

        if (!currentConfig.contains("\"tags\":{\"enabled\":false}")) {
            return
        }

        val updatedConfig =
            currentConfig.replace(
                "\"tags\":{\"enabled\":false}",
                "\"tags\":{\"enabled\":true}",
            )

        val putResponse = client.updateSystemConfig(apiKey, updatedConfig)

        check(putResponse.status in 200..299) {
            "Failed to enable tags feature: ${putResponse.status}"
        }
    } */

    // /////////////////////////////////////////////////////////////////////////
}

fun getHandledContentTypeFromFileType(fileType: String): HandledContentType =
    when (fileType) {
        "jpeg", "jpg", "png", "pgx", "pam", "pnm", "pgm", "ppm", "pfm", "gif", "exr",
        -> HandledContentType.IMAGE_TO_JPEG_XL
        "3gp", "3gpp", "avi", "flv", "m4v", "mkv", "mts", "m2ts", "m2t", "mp4", "insv",
        "mpg", "mpe", "mpeg", "mov", "webm", "wmv",
        -> HandledContentType.VIDEO_TO_H265
        "jxl",
        -> HandledContentType.UNHANDLED_IMAGE
        else
        -> HandledContentType.UNKNOWN
    }

enum class ContentType { UNHANDLED, UNKNOWN, IMAGE, VIDEO }

// TODO: rename
enum class HandledContentType(
    val contentType: ContentType,
) {
    UNHANDLED_IMAGE(ContentType.UNHANDLED),
    UNKNOWN(ContentType.UNKNOWN),
    IMAGE_TO_JPEG_XL(ContentType.IMAGE),
    VIDEO_TO_H265(ContentType.VIDEO),
}
