@file:UseSerializers(UUIDSerializer::class)

package services

import api.QueuedAssetsResponse
import api.StagedAssetDto
import database.AssetStagingAreaRepository
import database.UserId
import database.UserInfo
import database.UserInfoRepository
import database.getById
import internal.serdes.UUIDSerializer
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@ApplicationScoped
class UserInfoService(
    private val assetStagingAreaRepository: AssetStagingAreaRepository,
    private val immichService: ImmichService,
    private val userInfoRepository: UserInfoRepository,
) {
    fun listUsers(): List<UserInfo> = userInfoRepository.listAll()

    fun upsertUser(dto: UserAddParams) =
        with(dto) {
            // Validate the supplied API key against the supplied server, before touching the DB.
            if (!isApiKeyValid(immichServerUrl, apiKey)) {
                throw WebApplicationException(
                    Response
                        .status(Response.Status.BAD_REQUEST)
                        .type(MediaType.TEXT_PLAIN)
                        .entity("Immich rejected this API key — the user was not saved.")
                        .build(),
                )
            }

            val tagId = immichService.upsertTag(apiKey, immichService.instantiateClient(immichServerUrl)).id

            // Only now open a transaction to persist the change (flushed on commit).
            QuarkusTransaction.requiringNew().run {
                if (id == null) {
                    userInfoRepository
                        .persist(
                            UserInfo(
                                apiKey = apiKey,
                                name = name,
                                immichServerUrl = immichServerUrl,
                                _tagUUID = tagId.toString(),
                            ),
                        )
                } else {
                    userInfoRepository.getById(id).also {
                        it.name = name
                        it.immichServerUrl = immichServerUrl
                        it.apiKey = apiKey
                    }
                }
            }
        }

    fun userDetails(userId: UserId): UserDetails =
        userInfoRepository.getById(userId.value).let { user ->
            val client = immichService.instantiateClient(user.immichServerUrl)

            UserDetails(
                userInfo = user,
                serverVersion =
                    runCatching { client.serverAbout(user.apiKey).version }
                        .getOrNull()
                        .let { requireNotNull(it) { "Unable to retrieve the server info." } },
            ).also { ud ->
                val (major, minor, _) =
                    ud.serverVersion
                        .replace("v", "")
                        .split(".")
                        .map(String::toInt)

                val errorMsg = "The server is running an unsupported major version of $major."

                require(major in 3..<4) { errorMsg }

                if (major == 3) {
                    require(minor >= 2) { errorMsg }
                }
            }
        }

    fun userQueuedAssets(
        id: UserId,
        page: Int,
        size: Int,
        contentTypes: List<String>?,
    ): QueuedAssetsResponse {
        val safePage = page.coerceAtLeast(1)

        val safeSize = size.coerceIn(1, 1000)

        val pageIndex = safePage - 1

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

    // ////////////////////////////

    private fun isApiKeyValid(
        immichServerUrl: String,
        apiKey: String,
    ): Boolean =
        runCatching {
            immichService.instantiateClient(immichServerUrl).authValidateToken(apiKey).authStatus
        }.getOrDefault(false)

    companion object {
        @Serializable
        data class UserAddParams(
            val id: Int? = null,
            val name: String,
            val immichServerUrl: String,
            val apiKey: String,
        )
    }
}

@Serializable
data class UserDetails(
    val userInfo: UserInfo,
    val serverVersion: String,
)
