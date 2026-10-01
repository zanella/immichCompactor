@file:UseSerializers(UUIDSerializer::class)

package api

import database.AssetStagingAreaRepository
import database.UserId
import database.UserInfo
import database.UserInfoRepository
import database.getById
import internal.serdes.UUIDSerializer
import io.quarkus.narayana.jta.QuarkusTransaction
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
import services.ContentType
import services.ImmichService
import java.util.UUID

// TODO: clean it up :-), move logic to service
@Path("/api/users")
class UserResource(
    private val assetConversionService: AssetConversionService,
    private val assetRefreshJobService: AssetRefreshJobService,
    private val assetStagingAreaRepository: AssetStagingAreaRepository,
    private val immichService: ImmichService,
    private val userInfoRepository: UserInfoRepository,
) {
    @GET
    @Produces(APPLICATION_JSON)
    fun listUsers(): List<UserInfo> = userInfoRepository.listAll()

    data class UserAddParams(
        val id: Int? = null,
        val name: String,
        val immichServerUrl: String,
        val apiKey: String,
    )

    @POST
    @Produces(APPLICATION_JSON)
    fun upsertUser(dto: UserAddParams): Unit =
        with(dto) {
            if (name.isBlank() || immichServerUrl.isBlank() || apiKey.isBlank()) {
                throw IllegalStateException("All fields are required.")
            }

            // Validate the supplied API key against the supplied server, before touching the DB.
            if (!apiKeyIsValid(immichServerUrl, apiKey)) {
                throw IllegalStateException("Immich rejected this API key — the user was not saved.")
            }

            immichService.upsertTag(apiKey, immichService.instantiateClient(immichServerUrl))

            // TODO: move to service
            // Only now open a transaction to persist the change (flushed on commit).
            QuarkusTransaction.requiringNew().run {
                if (id == null) {
                    userInfoRepository
                        .persist(UserInfo(apiKey = apiKey, name = name, immichServerUrl = immichServerUrl))
                } else {
                    userInfoRepository.getById(id).also {
                        it.name = name
                        it.immichServerUrl = immichServerUrl
                        it.apiKey = apiKey
                    }
                }
            }
        }

    @GET
    @Path("/{id}")
    @Produces(APPLICATION_JSON)
    fun userDetails(
        @PathParam("id") rawUserId: String,
    ): UserDetails =
        userInfoRepository.getById(UserId(rawUserId.toInt()).value).let { user ->
            // TODO: check it's >= 2
            val client = immichService.instantiateClient(user.immichServerUrl)

            UserDetails(
                user,
                runCatching { client.serverAbout(user.apiKey).version }.getOrNull(),
                runCatching { immichService.upsertTag(user.apiKey, client).id }.getOrNull(),
            )
        }

    @GET
    @Path("/{id}/assets/status")
    @Produces(APPLICATION_JSON)
    fun refreshStatus(
        @PathParam("id") rawUserId: String,
    ): AssetRefreshJobService.JobStatus? = assetRefreshJobService.status(UserId(rawUserId.toInt()))

    @POST
    @Path("/{id}/assets/refresh")
    @Produces(APPLICATION_JSON)
    fun refreshAssets(
        @PathParam("id") rawUserId: String,
    ): AssetRefreshJobService.JobStatus {
        val id = UserId(rawUserId.toInt())

        val user = userInfoRepository.getById(id.value)

        return assetRefreshJobService.start(id, user.apiKey, user.immichServerUrl)
    }

    @GET
    @Path("/{id}/assets/queued")
    @Produces(APPLICATION_JSON)
    fun queuedAssets(
        @PathParam("id") rawUserId: String,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
        @QueryParam("contentTypes") @Parameter(required = false) contentTypes: List<String>?,
    ): QueuedAssetsResponse {
        val safePage = page.coerceAtLeast(1)

        val safeSize = size.coerceIn(1, 1000)

        val pageIndex = safePage - 1

        val id = UserId(rawUserId.toInt())

        val selectedContentTypes =
            contentTypes
                ?.mapNotNull { runCatching { ContentType.valueOf(it) }.getOrNull() }
                ?.minus(ContentType.UNHANDLED)
                ?: emptyList()

        val items =
            assetStagingAreaRepository
                .findQueuedByUserId(id, pageIndex, safeSize, selectedContentTypes)

        val total = assetStagingAreaRepository.countQueuedByUserId(id, selectedContentTypes)

        val pageCount = ((total + safeSize - 1) / safeSize).toInt().coerceAtLeast(1)

        return QueuedAssetsResponse(
            assets = items.map { StagedAssetDto(it.assetId, it.contentType.name, it.currentState.name) },
            page = safePage,
            size = safeSize,
            total = total,
            pageCount = pageCount,
            hasPrev = safePage > 1,
            hasNext = safePage < pageCount,
            contentTypes = ContentType.entries.filter { it != ContentType.UNHANDLED }.map { it.name },
        )
    }

    @POST
    @Path("/{id}/assets/{assetId}/convert")
    @Produces(APPLICATION_JSON)
    fun convertAsset(
        @PathParam("id") rawUserId: String,
        @PathParam("assetId") assetId: UUID,
    ): ConvertResultResponse {
        val id = UserId(rawUserId.toInt())
        val user = userInfoRepository.getById(id.value)
        val client = immichService.instantiateClient(user.immichServerUrl)

        return try {
            val newAssetId = assetConversionService.processQueuedAsset(client, user, assetId)

            if (newAssetId == null) {
                ConvertResultResponse(status = "removed", newAssetId = null, immichServerUrl = null, error = null)
            } else {
                ConvertResultResponse(
                    status = "converted",
                    newAssetId = newAssetId,
                    immichServerUrl = user.immichServerUrl.trimEnd('/'),
                    error = null,
                )
            }
        } catch (e: Exception) {
            ConvertResultResponse(status = "error", newAssetId = null, immichServerUrl = null, error = e.message)
        }
    }

    private fun apiKeyIsValid(
        immichServerUrl: String,
        apiKey: String,
    ): Boolean =
        runCatching {
            immichService.instantiateClient(immichServerUrl).authValidateToken(apiKey).authStatus
        }.getOrDefault(false)
}

@Serializable
data class UserDetails(
    val userInfo: UserInfo,
    val serverVersion: String?,
    val tagId: UUID?,
)

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
    val newAssetId: UUID?,
    val immichServerUrl: String?,
    val error: String?,
)
