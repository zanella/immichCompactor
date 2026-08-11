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
import integration.immich.SearchAssetsRequest
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
import java.time.Instant
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
        isDebug: Boolean = false,
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
                    // .register(ImmichLoggingFilter::class.java)
                }
            }.build(ImmichClient::class.java)

    fun getAssetInfo(
        apiKey: String,
        assetId: UUID,
        client: ImmichClient,
    ): AssetResponseDto? {
        val response = client.getAssetInfo(apiKey, assetId)

        println("Get Asset Info: $response")

        // TODO: Immich returns 400, instead of 404, if the asset is not found...
        if (response.status == 400) {
            return null
        } else if (response.status != 200) {
            throw RuntimeException("Download failed with HTTP Status: ${response.status}")
        }

        return response.entity as? AssetResponseDto
    }

    fun findAllAssets(
        apiKey: String,
        client: ImmichClient,
        page: Int = 1,
    ): SearchAssetsResponse {
        val searchCriteria =
            SearchAssetsRequest(
                createdAfter = Instant.EPOCH.toString(),
                page = page,
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
                        .filter { it.currentState == AssetConversionStates.QUEUED }
                        .map { it.assetId }
                        .toSet()

                // TODO: If the amount is huge -> think how to handle
                val convertedAssetsIds = convertedAssetsRepository.listAll().map { it.assetId }.toSet()

                stagedAssetsIds + convertedAssetsIds
            }

        var assetsFound = 0L
        var assetsQueued = 0L
        var page: Int? = 1

        while (page != null) {
            val searchAssetsResponse = findAllAssets(apiKey, client, page).assets

            val assetsToBeStaged =
                searchAssetsResponse.items
                    .filter {
                        if (it.tags.isNotEmpty()) {
                            println("Found ${it.tags} tags")
                        }

                        // If not tagged by immichCompactor -> not converted by it :-)
                        it.tags
                            .filter { tag -> tag.name == IMMICH_COMPACTOR_TAG_NAME }
                            .toSet()
                            .isEmpty()
                    }.filter { !skippableAssets.contains(it.id) }
                    .filter { !it.isTrashed }
                    .map { asset ->
                        StagedAsset(
                            userId = userId,
                            assetId = asset.id,
                            currentState = AssetConversionStates.QUEUED,
                        )
                    }

            // Short per-page transaction, so no DB connection is held while paging
            // through the network.
            QuarkusTransaction.requiringNew().run {
                assetStagingAreaRepository.persist(assetsToBeStaged)
            }

            assetsFound += searchAssetsResponse.items.size
            assetsQueued += assetsToBeStaged.size

            page = searchAssetsResponse.nextPage?.toInt()
        }

        return FindAndEnqueueAllAssetsResponse(assetsFound = assetsFound, assetsQueued = assetsQueued)
    }

    fun downloadAssetToDisk(
        apiKey: String,
        assetId: UUID,
        client: ImmichClient,
        targetOutputFile: File,
    ): Pair<MediaType, HandledContentType> {
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

        return response
            .getHeaderString("Content-Type")
            .let { MediaType.valueOf(it) to getSuffixFromMimeType(it) }
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

    private fun getSuffixFromMimeType(contentType: String?): HandledContentType {
        if (contentType.isNullOrBlank()) return HandledContentType.UNKNOWN

        val subType =
            contentType
                .substringBefore(";")
                .trim()
                .lowercase()
                .substringAfter("/", missingDelimiterValue = "")

        return when (subType) {
            "jpeg", "jpg" -> HandledContentType.IMAGE_JPEG
            "png" -> HandledContentType.IMAGE_PNG
            /* "quicktime" -> "mov"
            "x-matroska" -> "mkv"
            "octet-stream" -> "bin" */
            else -> HandledContentType.UNKNOWN
        }
    }
}

enum class HandledContentType {
    UNKNOWN,
    IMAGE_JPEG,
    IMAGE_PNG,
}
