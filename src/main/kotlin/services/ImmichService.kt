package services

import database.AssetConversionStates
import database.AssetStagingAreaRepository
import database.ConvertedAssetsRepository
import database.StagedAsset
import database.UserId
import integration.immich.AssetMediaResponseDto
import integration.immich.AssetResponseDto
import integration.immich.CreateTagRequest
import integration.immich.IMMICH_COMPACTOR_TAG_NAME
import integration.immich.ImmichClient
import integration.immich.ImmichLoggingFilter
import integration.immich.SearchAssetsRequest
import integration.immich.SearchAssetsRequest.Companion.SearchFilter
import integration.immich.SearchAssetsRequest.Companion.SearchFilter.Companion.IdsFilter
import integration.immich.SearchAssetsResponse
import integration.immich.TagResponseDto
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.RestClientBuilder
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
    private val convertedAssetsRepository: ConvertedAssetsRepository,
) {
    /**
     * I don't want to wrap the whole API, leak the client for now and decide later if it should be hidden
     */
    fun instantiateClient(
        baseUrl: String,
        isDebug: Boolean = true,
    ): ImmichClient =
        RestClientBuilder
            .newBuilder()
            .baseUri(URI.create(baseUrl))
            .property("microprofile.rest.client.disable.default.mapper", true)
            .also {
                if (isDebug) {
                    // 1. Enable built-in network logging scope
                    it.property("quarkus.rest-client.logging.scope", "request-response")
                    // 2. Set max body log limits in characters
                    it.property("quarkus.rest-client.logging.body-limit", "50000")
                    it.register(ImmichLoggingFilter::class.java)
                }
            }.build(ImmichClient::class.java)

    fun getAssetInfo(
        apiKey: String,
        assetId: UUID,
        client: ImmichClient,
    ): AssetResponseDto? {
        val response = client.getAssetInfo(apiKey, assetId)

        // TODO: Immich returns 400, instead of 404, if the asset is not found...
        if (response.status == 400) {
            return null
        } else if (response.status != 200) {
            throw RuntimeException("Download failed with HTTP Status: ${response.status}")
        }

        return response.readEntity(AssetResponseDto::class.java)
    }

    fun findAllAssets(
        apiKey: String,
        client: ImmichClient,
        tagId: UUID,
        cursor: String? = null,
    ): SearchAssetsResponse {
        val searchCriteria =
            SearchAssetsRequest(
                filter =
                    SearchFilter(
                        tagIds =
                            IdsFilter(
                                none = listOf(tagId),
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
        apiKey: String,
        userId: UserId,
        client: ImmichClient,
    ): FindAndEnqueueAllAssetsResponse {
        /*
         * TODO: https://api.immich.app/endpoints/jobs/getQueuesLegacy
         *
         * The endpoint above is deprecated, so... can't be sure there are no jobs running :-/
         */

        // This runs on a bare virtual thread (no CDI request context), so DB access needs
        // explicit transactions — Panache's built-in reads only *join* a transaction,
        // they can't start one themselves.
        val skippableAssets =
            QuarkusTransaction.joiningExisting().call {
                val stagedAssetsIds =
                    assetStagingAreaRepository
                        .listAll()
                        // TODO: filter on DB query, instead of return
                        .filter { it.currentState != AssetConversionStates.WAITING_DELETION }
                        .map { it.assetId }
                        .toSet()

                // TODO: If the amount is huge -> think how to handle
                val convertedAssetsIds = convertedAssetsRepository.listAll().map { it.assetId }.toSet()

                stagedAssetsIds + convertedAssetsIds
            }

        var assetsFound = 0L
        var assetsQueued = 0L
        var nextCursor: String? = null

        // TODO: add this to the userInfo, and fetch once
        val tagId = upsertTag(apiKey, client).id

        do {
            val searchAssetsResponse = findAllAssets(apiKey, client, tagId, nextCursor).assets

            val assetsToBeStaged =
                searchAssetsResponse.items
                    .filter { !skippableAssets.contains(it.id) }
                    .filter { !it.isTrashed }
                    .filter { asset ->
                        val tagFound =
                            getAssetInfo(apiKey, asset.id, client)
                                ?.tags
                                ?.filter { tag -> tag.name == IMMICH_COMPACTOR_TAG_NAME }
                                ?.toSet()
                                ?: emptySet()

                        if (!tagFound.isEmpty()) {
                            println("Asset [${asset.id}] is already tagged, skipping it")
                            false
                        } else {
                            true
                        }
                    }.map { asset ->
                        StagedAsset(
                            userId = userId,
                            assetId = asset.id,
                            contentType =
                                splitFileName(asset.originalFileName)
                                    .second
                                    .let(::getHandledContentTypeFromFileType)
                                    .contentType,
                            currentState = AssetConversionStates.QUEUED,
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

        println("Successfully wrote asset binary stream to: ${targetOutputFile.absolutePath}")
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
