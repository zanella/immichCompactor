package api

import database.AssetStagingAreaRepository
import database.UserId
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
import services.AssetRefreshJobService
import services.ImmichService

@Path("/")
class UserResource(
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
    fun index(): TemplateInstance =
        users
            .instance()
            .data("users", userInfoRepository.listAll())

    @GET
    @Path("/users/{id}")
    @Produces(MediaType.TEXT_HTML)
    fun user(
        @PathParam("id") id: UserId,
    ): TemplateInstance =
        userInfoRepository.getById(id.value).let {
            userData(userDetails.instance(), id, it.name, it.immichServerUrl, it.apiKey)
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
    @Path("/users/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun updateUser(
        @PathParam("id") id: UserId,
        @FormParam("name") name: String,
        @FormParam("immichServerUrl") immichServerUrl: String,
        @FormParam("apiKey") apiKey: String,
    ): TemplateInstance {
        // Validate the supplied API key against the supplied server, before touching the DB.
        val valid =
            runCatching {
                immichService.instantiateClient(immichServerUrl).authValidateToken(apiKey).authStatus
            }.getOrDefault(false)

        if (!valid) {
            return userData(
                userForm.instance(),
                id,
                name,
                immichServerUrl,
                apiKey,
                error = "Immich rejected this API key — changes were not saved.",
            )
        }

        // Only now open a transaction to persist the edit (flushed on commit).
        QuarkusTransaction.requiringNew().run {
            userInfoRepository.getById(id.value).also {
                it.name = name
                it.immichServerUrl = immichServerUrl
                it.apiKey = apiKey
            }
        }

        return userData(userForm.instance(), id, name, immichServerUrl, apiKey, saved = true)
    }

    private fun userData(
        instance: TemplateInstance,
        id: UserId,
        name: String,
        immichServerUrl: String,
        apiKey: String,
        saved: Boolean = false,
        error: String? = null,
    ): TemplateInstance =
        instance
            .data("id", id.value)
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
