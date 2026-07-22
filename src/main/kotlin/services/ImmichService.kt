package services

import integration.immich.AssetMultipartUpload
import integration.immich.AssetResponseDto
import integration.immich.ImmichClient
import integration.immich.ImmichLoggingFilter
import integration.immich.SearchAssetsRequest
import integration.immich.SearchAssetsResponse
import io.vertx.core.buffer.Buffer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@ApplicationScoped
class ImmichService {

    val client: ImmichClient by lazy {
        val dynamicBaseUrl = "http://localhost:2283"

        RestClientBuilder.newBuilder()
            .baseUri(URI.create(dynamicBaseUrl))
            // 1. Enable built-in network logging scope
            .property("quarkus.rest-client.logging.scope", "request-response")
            // 2. Set max body log limits in characters
            .property("quarkus.rest-client.logging.body-limit", "50000")
            //.register(ImmichLoggingFilter::class.java)
            .build(ImmichClient::class.java)
    }

    fun findRecentAssets(apiKey: String): SearchAssetsResponse {

        // Generate an ISO 8601 timestamp string for exactly 30 days ago
        val thirtyDaysAgoIsoString = Instant.now()
            .minus(30, ChronoUnit.DAYS)
            .toString()

        val searchCriteria = SearchAssetsRequest(
            createdAfter = thirtyDaysAgoIsoString,
        )

        return client.searchByMetadata(apiKey, searchCriteria)
    }

    fun downloadAssetToDisk(
        apiKey: String,
        assetId: UUID,
        targetOutputFile: File
    ): Pair<MediaType, HandledContentType> {
        // 1. Fire the request and obtain the network socket reference
        val response = client.assetDownloadOriginal(apiKey, assetId)

        // 2. Validate response code explicitly before touching the stream content
        if (response.status != 200) {
            throw RuntimeException("Download failed with HTTP Status: ${response.status}")
        }

        // 3. Extract the binary input stream safely
        val stream = response.entity as? InputStream
            ?: throw IllegalStateException("Response body does not contain an stream entity")

        stream.use { input ->
            // 4. Efficiently pipe stream blocks directly to disk (low memory usage)
            Files.copy(input, targetOutputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        println("Successfully wrote asset binary stream to: ${targetOutputFile.absolutePath}")

        return response.getHeaderString("Content-Type")
            .let { MediaType.valueOf(it) to getSuffixFromMimeType(it) }
    }

    /* fun uploadLocalFile(
        apiKey: String,
        assetResponse: AssetResponseDto,
        fileToUpload: File,
    ): String {
        // Prepare the multi-part data payload container object
        val uploadForm = AssetMultipartUpload().apply {
            this.assetData = FileInputStream(fileToUpload)

            // TODO: what to put here ?
            this.deviceId = "quarkus-backend"
            // Immich identifies unique items based on deviceAssetId matching names + stamps
            this.deviceAssetId = "${fileToUpload.name}-${assetResponse.fileCreatedAt}"

            this.fileCreatedAt = assetResponse.fileCreatedAt
            this.fileModifiedAt = assetResponse.fileModifiedAt
        }

        return uploadForm.assetData.use {
            client.uploadAsset(apiKey, uploadForm)
        }
    } */

    fun uploadLocalFile(
        apiKey: String,
        assetResponse: AssetResponseDto,
        fileToUpload: File,
        mediaType: MediaType,
    ): String {
        // Programmatically build the unified modern multipart form
        val form = ClientMultipartForm.create()
            .binaryFileUpload("assetData", fileToUpload.name, fileToUpload.absolutePath, mediaType.toString())
            .attribute("deviceId", "quarkus-blocking-backend", "")
            .attribute("deviceAssetId", "${fileToUpload.name}-${assetResponse.fileCreatedAt}", "")
            .attribute("fileCreatedAt", assetResponse.fileCreatedAt, "")
            .attribute("fileModifiedAt", assetResponse.fileModifiedAt, "")

        // Fires a standard synchronous blocking call
        return client.uploadAsset(apiKey, form)
    }

    private fun getSuffixFromMimeType(contentType: String?): HandledContentType {
        if (contentType.isNullOrBlank()) return HandledContentType.UNKNOWN

        val subType = contentType.substringBefore(";").trim().lowercase()
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
