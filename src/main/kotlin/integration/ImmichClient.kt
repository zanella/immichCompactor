package integration

import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import kotlinx.serialization.Serializable
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import org.jboss.logging.Logger
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientResponseFilter
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

@Serializable
data class AuthStatusResponse(
    val expiresAt: String? = null,
    val isElevated: Boolean,
    val password: Boolean,
    val pinCode: Boolean,
    val pinExpiresAt: String? = null,
)

@Serializable
data class AuthValidateTokenResponse(val authStatus: Boolean)


///////////

@Serializable
data class SearchAssetsRequest(
    val city: String? = null,
    val country: String? = null,
    val createdAfter: String? = null, // Formatted as ISO 8601 string
    val isFavorite: Boolean? = null,
    val isOffline: Boolean? = null,
    val make: String? = null,
    val model: String? = null,
    val withExif: Boolean? = null
)


@Serializable
data class SearchMetadataResponse(
    //val albums: , // SearchAlbumResponseDto
    val assets: SearchAssetResponseDto
)

// 2. The inner container holding the array list
@Serializable
data class SearchAssetResponseDto(
    val count: Int,
    //val facets
    val total: Int,
    val items: List<AssetResponseDto>
)

// 3. The actual asset items inside the array list
@Serializable
data class AssetResponseDto(
    val checksum: String,
    val createdAt: String, // DateTime
    val fileCreatedAt: String, // DateTime
    val id: String,
    val type: String, // "IMAGE" or "VIDEO"
    val ownerId: String,
    val originalPath: String? = null,
    val isFavorite: Boolean? = false,
    val width: Int? = null,
    val height: Int? = null
)

///////////

@Path("/api")
interface ImmichClient {

    @GET
    @Path("/auth/status")
    fun authStatus(@HeaderParam(X_API_KEY) apiKey: String): AuthStatusResponse

    @POST
    @Path("/auth/validateToken")
    fun authValidateToken(@HeaderParam(X_API_KEY) apiKey: String): AuthValidateTokenResponse

    ///////////////////////////////////////////////////////////////////////////

    @POST
    @Path("/search/metadata")
    fun searchByMetadata(
        @HeaderParam(X_API_KEY) apiKey: String,
        requestBody: SearchAssetsRequest
    ): SearchMetadataResponse

    companion object {
        const val X_API_KEY = "x-api-key"
    }
}

///////////////////////////////////////

class ImmichLoggingFilter : ClientRequestFilter, ClientResponseFilter {
    private val log = Logger.getLogger(ImmichLoggingFilter::class.java)

    // 1. Intercept Outgoing Request
    override fun filter(requestContext: ClientRequestContext) {
        val method = requestContext.method
        val uri = requestContext.uri
        log.info("-----> Sending Request: $method $uri")

        // Log headers securely
        requestContext.headers.forEach { (key, values) ->
            val displayValue = if (key.equals("x-api-key", ignoreCase = true)) "******" else values.joinToString()
            log.info("Request Header: $key = $displayValue")
        }

        // Peek Request Body safely if an entity object exists
        if (requestContext.hasEntity()) {
            val entityObj = requestContext.entity
            log.info("Request Payload Object Type: ${entityObj?.javaClass?.name}")

            // Note: In client filters, requestContext.entity contains the raw data class object (e.g. SearchAssetsRequest)
            // before it is serialized by the JSON extension.
            log.info("Request Body Data: $entityObj")
        }
    }

    // 2. Intercept Incoming Response (Safely buffering streams)
    @Throws(IOException::class)
    override fun filter(requestContext: ClientRequestContext, responseContext: ClientResponseContext) {
        val status = responseContext.status
        val statusInfo = responseContext.statusInfo
        log.info("<--- Received Response Status: $status $statusInfo")

        // Peek Response JSON body safely if it exists
        if (responseContext.hasEntity()) {
            val entityStream = responseContext.entityStream
            if (entityStream != null) {
                val bytes = entityStream.readAllBytes()
                val responseBodyString = String(bytes, StandardCharsets.UTF_8)

                log.info("Response Body Payload:\n$responseBodyString")

                // Reset the response stream buffer so your ImmichClient can deserialize it
                responseContext.entityStream = ByteArrayInputStream(bytes)
            }
        }
    }
}
