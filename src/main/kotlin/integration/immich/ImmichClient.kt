package integration.immich

import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.HeaderParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientResponseFilter
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.client.api.ClientMultipartForm
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID

@Path("/api")
interface ImmichClient {
    @GET
    @Path("/auth/status")
    fun authStatus(
        @HeaderParam(X_API_KEY) apiKey: String,
    ): AuthStatusResponse

    @POST
    @Path("/auth/validateToken")
    fun authValidateToken(
        @HeaderParam(X_API_KEY) apiKey: String,
    ): AuthValidateTokenResponse

    // /////////////////////////////////////////////////////////////////////////

    @POST
    @Path("/search/metadata")
    fun searchByMetadata(
        @HeaderParam(X_API_KEY) apiKey: String,
        requestBody: SearchAssetsRequest,
    ): SearchAssetsResponse

    companion object {
        const val X_API_KEY = "x-api-key"
    }

    // /////////////////////////////////////////////////////////////////////////

    @GET
    @Path("/assets/{id}/original")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    fun assetDownloadOriginal(
        @HeaderParam(X_API_KEY) apiKey: String,
        @PathParam("id") assetId: UUID,
    ): Response

    @POST
    @Path("/assets")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    fun uploadAsset(
        @HeaderParam(X_API_KEY) apiKey: String,
        multipartForm: ClientMultipartForm,
    ): AssetMediaResponseDto

    @PUT
    @Path("/assets/copy")
    fun copyAsset(
        @HeaderParam(X_API_KEY) apiKey: String,
        requestBody: CopyAssetRequest,
    )

    @GET
    @Path("/assets/{id}")
    fun getAssetInfo(
        @HeaderParam(X_API_KEY) apiKey: String,
        @PathParam("id") assetId: UUID,
    ): AssetResponseDto
}

// /////////////////////////////////////

class ImmichLoggingFilter :
    ClientRequestFilter,
    ClientResponseFilter {
    private val log = Logger.getLogger(ImmichLoggingFilter::class.java)

    @Throws(IOException::class)
    override fun filter(requestContext: ClientRequestContext) {
        val method = requestContext.method
        val uri = requestContext.uri
        log.info("-----> Sending Request: $method $uri")

        requestContext.headers.forEach { (key, values) ->
            val displayValue = if (key.equals("x-api-key", ignoreCase = true)) "******" else values.joinToString()
            log.info("Request Header: $key = $displayValue")
        }

        if (requestContext.hasEntity()) {
            val entityObj = requestContext.entity
            log.info("Request Body Data: $entityObj")
        }
    }

    @Throws(IOException::class)
    override fun filter(
        requestContext: ClientRequestContext,
        responseContext: ClientResponseContext,
    ) {
        val status = responseContext.status
        val statusInfo = responseContext.statusInfo
        log.info("<--- Received Response Status: $status $statusInfo")

        if (responseContext.hasEntity()) {
            val contentType = responseContext.mediaType

            // SMART CHECK: Only peek at the body if it is text-based (JSON, text, etc.)
            if (isTextPayload(contentType)) {
                val entityStream = responseContext.entityStream
                if (entityStream != null) {
                    val bytes = entityStream.readAllBytes()
                    val responseBodyString = String(bytes, StandardCharsets.UTF_8)

                    log.info("Response Body Payload:\n$responseBodyString")

                    // Reset the stream so the client data class parser can read it
                    responseContext.entityStream = ByteArrayInputStream(bytes)
                }
            } else {
                // If it's a binary stream (image/png, video/mp4, octet-stream), do NOT read it
                log.info("Response Body: [Binary Content Hidden - Type: $contentType]")
            }
        }
    }

    // Helper to determine if the payload is safe to read as text strings
    private fun isTextPayload(mediaType: MediaType?): Boolean {
        if (mediaType == null) return false
        val subtype = mediaType.subtype.lowercase()
        val type = mediaType.type.lowercase()

        return type == "text" ||
            subtype == "json" ||
            subtype.endsWith("+json") ||
            subtype.endsWith("+xml")
    }
}
