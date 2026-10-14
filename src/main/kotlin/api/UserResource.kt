@file:UseSerializers(UUIDSerializer::class)

package api

import database.UserId
import database.UserInfo
import internal.lang.CustomEither
import internal.serdes.UUIDSerializer
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType.APPLICATION_JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter
import services.AssetConversionService
import services.AssetRefreshJobService
import services.UserDetails
import services.UserInfoService
import services.UserInfoService.Companion.UserAddParams
import java.util.UUID

@Path("/api/users")
class UserResource(
    private val assetConversionService: AssetConversionService,
    private val assetRefreshJobService: AssetRefreshJobService,
    private val userInfoService: UserInfoService,
) {
    @GET
    @Produces(APPLICATION_JSON)
    fun listUsers(): List<UserInfo> = userInfoService.listUsers()

    @POST
    @Produces(APPLICATION_JSON)
    fun upsertUser(dto: UserAddParams): Unit = userInfoService.upsertUser(dto)

    @GET
    @Path("/{id}")
    @Produces(APPLICATION_JSON)
    fun userDetails(
        @PathParam("id") rawUserId: String,
    ): UserDetails = UserId.from(rawUserId).let(userInfoService::userDetails)

    @GET
    @Path("/{id}/assets/status")
    @Produces(APPLICATION_JSON)
    fun refreshStatus(
        @PathParam("id") rawUserId: String,
    ): AssetRefreshJobService.AssetRefreshJobStatus? = UserId.from(rawUserId).let(assetRefreshJobService::status)

    @POST
    @Path("/{id}/assets/refresh")
    @Produces(APPLICATION_JSON)
    fun refreshAssets(
        @PathParam("id") rawUserId: String,
    ): AssetRefreshJobService.AssetRefreshJobStatus =
        UserId
            .from(rawUserId)
            .let(userInfoService::userDetails)
            .userInfo
            .let(assetRefreshJobService::start)

    @GET
    @Path("/{id}/assets/queued")
    @Produces(APPLICATION_JSON)
    fun queuedAssets(
        @PathParam("id") rawUserId: String,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
        @QueryParam("contentTypes") @Parameter(required = false) contentTypes: List<String>?,
    ): QueuedAssetsResponse =
        userInfoService
            .userQueuedAssets(UserId.from(rawUserId), page, size, contentTypes)

    @POST
    @Path("/{id}/assets/{assetId}/convert")
    @Produces(APPLICATION_JSON)
    fun convertAsset(
        @PathParam("id") rawUserId: String,
        @PathParam("assetId") assetId: UUID,
    ): ConvertResultResponse =
        try {
            when (val result = assetConversionService.processQueuedAsset(UserId.from(rawUserId), assetId)) {
                is CustomEither.Left ->
                    ConvertResultResponse(
                        status = "converted",
                        newAssetId = result.value.second,
                        immichServerUrl =
                            result.value.first.immichServerUrl
                                .trimEnd('/'),
                    )
                is CustomEither.Right ->
                    return ConvertResultResponse(status = "removed", error = result.value)
            }
        } catch (e: Exception) {
            ConvertResultResponse(status = "error", error = e.message)
        }
}

@Serializable
data class StagedAssetDto(
    val assetId: UUID,
    val contentType: String,
    val currentState: String,
)

@Serializable
data class QueuedAssetsResponse(
    val assets: List<StagedAssetDto>,
    val page: Int,
    val size: Int,
    val total: Long,
    val pageCount: Int,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    val contentTypes: List<String>,
)

@Serializable
data class ConvertResultResponse(
    val status: String,
    val newAssetId: UUID? = null,
    val immichServerUrl: String? = null,
    val error: String? = null,
)
