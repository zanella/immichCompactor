package api

import database.AssetStagingAreaRepository
import database.UserInfo
import database.UserInfoRepository
import database.getById
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType.APPLICATION_JSON
import services.AssetConversionService
import services.AssetRefreshJobService
import services.ImmichService

data class UserAddParams(
    val id: Int? = null,
    val name: String,
    val immichServerUrl: String,
    val apiKey: String,
)

// TODO: clean it up :-), move logic to service
@Path("/api/users")
class UserResource(
    private val assetConversionService: AssetConversionService,
    private val assetRefreshJobService: AssetRefreshJobService,
    private val assetStagingAreaRepository: AssetStagingAreaRepository,
    private val immichService: ImmichService,
    private val userInfoRepository: UserInfoRepository,
    /*
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
    private val assetsList: Template, */
) {
    @GET
    @Produces(APPLICATION_JSON)
    fun listUsers(): List<UserInfo> = userInfoRepository.listAll()

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

    private fun apiKeyIsValid(
        immichServerUrl: String,
        apiKey: String,
    ): Boolean =
        runCatching {
            immichService.instantiateClient(immichServerUrl).authValidateToken(apiKey).authStatus
        }.getOrDefault(false)

    // TemplateInstance = userFormData(userAdd.instance(), id = null, name = "", immichServerUrl = "", apiKey = "")

    /*
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

            val tagId =
                runCatching {
                    immichService.upsertTag(user.apiKey, client).id
                }.getOrNull()

            userFormData(userDetails.instance(), id, user.name, user.immichServerUrl, user.apiKey)
                .data("serverVersion", serverVersion)
                .data("tagId", tagId)
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

    / ** TODO: move to service
     * Shared create/update flow: [id] == null means "create a new entry", otherwise the
     * existing entry is updated. Same validation either way.
     * /
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

        immichService.upsertTag(apiKey, immichService.instantiateClient(immichServerUrl))

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
    ): TemplateInstance {
        val user = userInfoRepository.getById(id.value)

        return assetsRefreshStatusFragment(id, user.name, assetRefreshJobService.status(id))
    }

    @POST
    @Path("/users/{id}/assets/refresh")
    @Produces(MediaType.TEXT_HTML)
    fun refreshAssets(
        @PathParam("id") id: UserId,
    ): TemplateInstance {
        val user =
            userInfoRepository.getById(id.value).also {
                assetRefreshJobService.start(id, it.apiKey, it.immichServerUrl)
            }

        return assetsRefreshStatusFragment(id, user.name, assetRefreshJobService.status(id))
    }

    @GET
    @Path("/users/{id}/assets/queued")
    @Produces(MediaType.TEXT_HTML)
    fun queuedAssets(
        @PathParam("id") id: UserId,
        @QueryParam("page") @DefaultValue("1") page: Int,
        @QueryParam("size") @DefaultValue("50") size: Int,
        @QueryParam("contentTypes") contentTypes: List<String>?,
    ): TemplateInstance {
        val safePage = page.coerceAtLeast(1)
        val safeSize = size.coerceIn(1, 1000)
        val pageIndex = safePage - 1

        val selectedContentTypes =
            contentTypes
                ?.mapNotNull { runCatching { ContentType.valueOf(it) }.getOrNull() }
                ?.minus(ContentType.UNHANDLED)
                ?: emptyList()

        val user = userInfoRepository.getById(id.value)
        val items = assetStagingAreaRepository.findQueuedByUserId(id, pageIndex, safeSize, selectedContentTypes)
        val total = assetStagingAreaRepository.countQueuedByUserId(id, selectedContentTypes)
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
            .data("contentTypes", ContentType.entries)
            .data("selectedContentTypes", selectedContentTypes.map { it.name })
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
            val newAssetId = assetConversionService.processQueuedAsset(client, user, assetId)

            println("newAssetId: $newAssetId")

            if (newAssetId == null) {
                // Trashed or not found — it was removed from the queue, nothing to link to.
                """
                <tr class="border-b border-gray-100 opacity-40 pointer-events-none select-none whitespace-nowrap">
                    <td class="py-2 px-3 text-gray-400 text-xs"></td>
                    <td class="py-2 px-3 font-mono text-xs text-gray-400 line-through">$assetId</td>
                    <td class="py-2 px-3"></td>
                    <td class="py-2 px-3">
                        <span class="inline-block px-2 py-0.5 text-xs font-semibold rounded-full bg-gray-100 text-gray-600">Removed</span>
                    </td>
                    <td class="py-2 px-3 text-right"></td>
                </tr>
                """.trim()
            } else {
                // Success — row in a disabled state, but with a link to the new asset.
                val baseUrl = user.immichServerUrl.trimEnd('/')

                // TODO: move to template
                """
                <tr class="border-b border-gray-100 select-none whitespace-nowrap">
                    <td class="py-2 px-3 text-gray-400 text-xs"></td>
                    <td class="py-2 px-3 font-mono text-xs text-gray-400 line-through">$assetId</td>
                    <td class="py-2 px-3"></td>
                    <td class="py-2 px-3">
                        <span class="inline-block px-2 py-0.5 text-xs font-semibold rounded-full bg-green-100 text-green-800">Converted</span>
                    </td>
                    <td class="py-2 px-3 text-right">
                        <div class="inline-flex items-center gap-2">
                            <a href="$baseUrl/photos/$assetId"
                               target="_blank"
                               rel="noopener noreferrer"
                               class="inline-flex items-center gap-1 px-2 py-1 border-2 border-red-500 text-red-700 bg-white text-xs font-bold rounded hover:bg-red-50 transition-colors whitespace-nowrap"><span>View OLD</span><svg class="w-3 h-3 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"/></svg></a>
                            <a href="$baseUrl/photos/$newAssetId"
                               target="_blank"
                               rel="noopener noreferrer"
                               class="inline-flex items-center gap-1 px-2 py-1 bg-indigo-600 text-white text-xs font-semibold rounded hover:bg-indigo-700 transition-colors whitespace-nowrap"><span>View new</span><svg class="w-3 h-3 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"/></svg></a>
                        </div>
                    </td>
                </tr>
                """.trim()
            }
        } catch (e: Exception) {
            println(e.stackTraceToString())

            "<tr><td colspan=\"5\" class=\"py-2 px-3 text-red-600 text-xs\">Conversion failed: ${e.message}</td></tr>"
        }
    }

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
        name: String,
        status: AssetRefreshJobService.JobStatus?,
    ): TemplateInstance =
        assetsStatus
            .instance()
            .data("id", id.value)
            .data("name", name)
            .data("running", status?.state == AssetRefreshJobService.State.RUNNING)
            .data("done", status?.state == AssetRefreshJobService.State.DONE)
            .data("failed", status?.state == AssetRefreshJobService.State.FAILED)
            .data("assetsFound", status?.assetsFound ?: 0)
            .data("assetsQueued", status?.assetsQueued ?: 0)
            .data("error", status?.error)
     */
}
