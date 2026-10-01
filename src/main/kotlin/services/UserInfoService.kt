@file:UseSerializers(UUIDSerializer::class)

package services

import database.UserId
import database.UserInfo
import database.UserInfoRepository
import database.getById
import internal.serdes.UUIDSerializer
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

@ApplicationScoped
class UserInfoService(
    private val immichService: ImmichService,
    private val userInfoRepository: UserInfoRepository,
) {
    fun listUsers(): List<UserInfo> = userInfoRepository.listAll()

    fun upsertUser(dto: UserAddParams) =
        with(dto) {
            // Validate the supplied API key against the supplied server, before touching the DB.
            if (!isApiKeyValid(immichServerUrl, apiKey)) {
                throw IllegalStateException("Immich rejected this API key — the user was not saved.")
            }

            immichService.upsertTag(apiKey, immichService.instantiateClient(immichServerUrl))

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

    fun userDetails(userId: UserId): UserDetails =
        userInfoRepository.getById(userId.value).let { user ->
            // TODO: check it's >= 2
            val client = immichService.instantiateClient(user.immichServerUrl)

            UserDetails(
                userInfo = user,
                serverVersion =
                    runCatching { client.serverAbout(user.apiKey).version }
                        .getOrNull()
                        .let { requireNotNull(it) { "Unable to retrieve the server info." } },
                tagId =
                    runCatching { immichService.upsertTag(user.apiKey, client).id }
                        .getOrNull()
                        .let { requireNotNull(it) { "Unable to retrieve the tagId." } },
            ).also { ud ->
                val majorVersion =
                    ud.serverVersion
                        .substringBefore('.')
                        .substring(1)
                        .toIntOrNull()

                require(majorVersion in 3..<4) {
                    "The server is running an unsupported major version of $majorVersion."
                }
            }
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
    val tagId: UUID?,
)
