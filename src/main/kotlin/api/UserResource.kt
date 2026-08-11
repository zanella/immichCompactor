package api

import database.AssetStagingAreaRepository
import database.UserId
import database.UserInfo
import database.UserInfoRepository
import database.getById
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import services.AssetConversionService
import services.AssetRefreshJobService
import services.ImmichService
import java.util.UUID

// TODO: clean it up :-), move logic to service
@Path("/")
class UserResource(
    private val assetConversionService: AssetConversionService,
    private val assetRefreshJobService: AssetRefreshJobService,
    private val assetStagingAreaRepository: AssetStagingAreaRepository,
    private val immichService: ImmichService,
    private val userInfoRepository: UserInfoRepository,
    //
    @param:Location("index.html")
    private val users: Template,
    //
    @param:Location("user_details.html")
    private val userDetails: Template,
    //
    @param:Location("user_form.html")
    private val userForm: Template,
    //
    @param:Location("user_add.html")
    private val userAdd: Template,
    //
    @param:Location("assets.html")
    private val assets: Template,
    //
    @param:Location("assets_status.html")
    private val assetsStatus: Template,
    //
    @param:Location("assets_list.html")
    private val assetsList: Template,
) {
    @GET
    @Produces(MediaType.TEXT_HTML)
    fun users(): TemplateInstance =
        users
            .instance()
            .data("users", userInfoRepository.listAll())

    @GET
    @Path("/users/new")
    @Produces(MediaType.TEXT_HTML)
    fun addUserForm(): TemplateInstance = userFormData(userAdd.instance(), id = null, name = "", immichServerUrl = "", apiKey = "")

    @POST
    @Path("/users")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun createUser(
        @FormParam("name") name: String,
        @FormParam("immichServerUrl") immichServerUrl: String,
        @FormParam("apiKey") apiKey: String,
    ): TemplateInstance = saveUser(id = null, name = name, immichServerUrl = immichServerUrl, apiKey = apiKey)

    @GET
    @Path("/users/{id}")
    @Produces(MediaType.TEXT_HTML)
    fun userDetails(
        @PathParam("id") id: UserId,
    ): TemplateInstance =
        userInfoRepository.getById(id.value).let { user ->
            // TODO: check it's >= 2
            val client = immichService.instantiateClient(user.immichServerUrl)

            val serverVersion =
                runCatching {
                    client.serverAbout(user.apiKey).version
                }.getOrNull()

            /* val tagId =
                runCatching {
                    immichService.upsertTag(user.apiKey, client).id
                }.getOrNull() */

            userFormData(userDetails.instance(), id, user.name, user.immichServerUrl, user.apiKey)
                .data("serverVersion", serverVersion)
            // .data("tagId", tagId)
        }

    @POST
    @Path("/users/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun updateUser(
        @PathParam("id") id: UserId,
        @FormParam("name") name: String,
        @FormParam("immichServerUrl") immichServerUrl: String,
        @FormParam("apiKey") apiKey: String,
    ): TemplateInstance = saveUser(id, name, immichServerUrl, apiKey)

    /** TODO: move to service
     * Shared create/update flow: [id] == null means "create a new entry", otherwise the
     * existing entry is updated. Same validation either way.
     */
    private fun saveUser(
        id: UserId?,
        name: String,
        immichServerUrl: String,
        apiKey: String,
    ): TemplateInstance {
        if (name.isBlank() || immichServerUrl.isBlank() || apiKey.isBlank()) {
            return userFormData(
                userForm.instance(),
                id,
                name,
                immichServerUrl,
                apiKey,
                error = "All fields are required.",
            )
        }

        // Validate the supplied API key against the supplied server, before touching the DB.
        if (!apiKeyIsValid(immichServerUrl, apiKey)) {
            return userFormData(
                userForm.instance(),
                id,
                name,
                immichServerUrl,
                apiKey,
                error = "Immich rejected this API key — the user was not saved.",
            )
        }

        // immichService.upsertTag(apiKey, immichService.instantiateClient(immichServerUrl))

        // Only now open a transaction to persist the change (flushed on commit).
        QuarkusTransaction.requiringNew().run {
            if (id == null) {
                userInfoRepository.persist(
                    UserInfo(apiKey = apiKey, name = name, immichServerUrl = immichServerUrl),
                )
            } else {
                userInfoRepository.getById(id.value).also {
                    it.name = name
                    it.immichServerUrl = immichServerUrl
                    it.apiKey = apiKey
                }
            }
        }

        return userFormData(userForm.instance(), id, name, immichServerUrl, apiKey, saved = true)
    }

    @GET
    @Path("/users/{id}/assets")
    @Produces(MediaType.TEXT_HTML)
    fun userAssets(
        @PathParam("id") id: UserId,
    ): TemplateInstance =
        userInfoRepository.getById(id.value).let {
            assets
                .instance()
                .data("id", id.value)
                .data("name", it.name)
        }

    @GET
    @Path("/users/{id}/assets/status")
    @Produces(MediaType.TEXT_HTML)
    fun assetsRefreshStatus(
        @PathParam("id") id: UserId,
    ): TemplateInstance = assetsRefreshStatusFragment(id, assetRefreshJobService.status(id))

    @POST
    @Path("/users/{id}/assets/refresh")
    @Produces(MediaType.TEXT_HTML)
    fun refreshAssets(
        @PathParam("id") id: UserId,
    ): TemplateInstance {
        userInfoRepository.getById(id.value).also {
            assetRefreshJobService.start(id, it.apiKey, it.immichServerUrl)
        }

        return assetsRefreshStatusFragment(id, assetRefreshJobService.status(id))
    }

    @GET
    @Path("/users/{id}/assets/queued")
    @Produces(MediaType.TEXT_HTML)
    fun queuedAssets(
        @PathParam("id") id: UserId,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
    ): TemplateInstance {
        val safePage = page.coerceAtLeast(1)
        val safeSize = size.coerceIn(1, 200)
        val pageIndex = safePage - 1

        val user = userInfoRepository.getById(id.value)
        val items = assetStagingAreaRepository.findQueuedByUserId(id, pageIndex, safeSize)
        val total = assetStagingAreaRepository.countQueuedByUserId(id)
        val pageCount = ((total + safeSize - 1) / safeSize).toInt().coerceAtLeast(1)

        return assetsList
            .instance()
            .data("id", id.value)
            .data("immichServerUrl", user.immichServerUrl.trimEnd('/'))
            .data("assets", items)
            .data("page", safePage)
            .data("size", safeSize)
            .data("total", total)
            .data("pageCount", pageCount)
            .data("hasPrev", safePage > 1)
            .data("hasNext", safePage < pageCount)
            .data("prevPage", (safePage - 1).coerceAtLeast(1))
            .data("nextPage", (safePage + 1).coerceAtMost(pageCount))
    }

    @POST
    @Path("/users/{id}/assets/{assetId}/convert")
    @Produces(MediaType.TEXT_HTML)
    fun convertAsset(
        @PathParam("id") id: UserId,
        @PathParam("assetId") assetId: UUID,
    ): String {
        val user = userInfoRepository.getById(id.value)

        val client = immichService.instantiateClient(user.immichServerUrl)

        return try {
            assetConversionService.processQueuedAssets(client, user, listOf(assetId))

            // Success — return the row in a disabled state
            """
            <tr class="border-b border-gray-100 opacity-40 pointer-events-none select-none">
                <td class="py-2 px-3 text-gray-400 text-xs"></td>
                <td class="py-2 px-3 font-mono text-xs text-gray-400 line-through">$assetId</td>
                <td class="py-2 px-3">
                    <span class="inline-block px-2 py-0.5 text-xs font-semibold rounded-full bg-green-100 text-green-800">Converted</span>
                </td>
                <td class="py-2 px-3 text-right"></td>
            </tr>
            """.trim()
        } catch (e: Exception) {
            println(e.stackTraceToString())

            "<tr><td colspan=\"4\" class=\"py-2 px-3 text-red-600 text-xs\">Conversion failed: ${e.message}</td></tr>"
        }
    }

    private fun apiKeyIsValid(
        immichServerUrl: String,
        apiKey: String,
    ): Boolean =
        runCatching {
            immichService.instantiateClient(immichServerUrl).authValidateToken(apiKey).authStatus
        }.getOrDefault(false)

    private fun userFormData(
        instance: TemplateInstance,
        id: UserId?,
        name: String,
        immichServerUrl: String,
        apiKey: String,
        saved: Boolean = false,
        error: String? = null,
    ): TemplateInstance =
        instance
            .data("id", id?.value)
            .data("formAction", if (id == null) "/users" else "/users/${id.value}")
            .data("name", name)
            .data("immichServerUrl", immichServerUrl)
            .data("apiKey", apiKey)
            .data("saved", saved)
            .data("error", error)

    private fun assetsRefreshStatusFragment(
        id: UserId,
        status: AssetRefreshJobService.JobStatus?,
    ): TemplateInstance =
        assetsStatus
            .instance()
            .data("id", id.value)
            .data("running", status?.state == AssetRefreshJobService.State.RUNNING)
            .data("done", status?.state == AssetRefreshJobService.State.DONE)
            .data("failed", status?.state == AssetRefreshJobService.State.FAILED)
            .data("assetsFound", status?.assetsFound ?: 0)
            .data("assetsQueued", status?.assetsQueued ?: 0)
            .data("error", status?.error)
}
