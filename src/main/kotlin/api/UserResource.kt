package api

import database.UserInfoRepository
import database.getById
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import services.ImmichService
import java.net.URI

@Path("/")
class UserResource(
    private val userInfoRepository: UserInfoRepository,
    @param:Location("index.html")
    private val users: Template,
    @param:Location("user_details.html")
    private val userDetails: Template,
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
        @PathParam("id") id: Long,
    ): TemplateInstance =
        userInfoRepository.getById(id).let {
            userForm(it.id, it.name, it.immichServerUrl, it.apiKey)
        }

    @POST
    @Path("/users/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun updateUser(
        @PathParam("id") id: Long,
        @FormParam("name") name: String,
        @FormParam("immichServerUrl") immichServerUrl: String,
        @FormParam("apiKey") apiKey: String,
    ): Response {
        // Validate the supplied API key against the supplied server, before touching the DB.
        val valid =
            runCatching {
                ImmichService(immichServerUrl).client.authValidateToken(apiKey).authStatus
            }.getOrDefault(false)

        if (!valid) {
            return Response
                .status(Response.Status.BAD_REQUEST)
                .entity(
                    userForm(id, name, immichServerUrl, apiKey)
                        .data("error", "Immich rejected this API key — changes were not saved."),
                ).build()
        }

        // Only now open a transaction to persist the edit (flushed on commit).
        QuarkusTransaction.requiringNew().run {
            userInfoRepository.getById(id).also {
                it.name = name
                it.immichServerUrl = immichServerUrl
                it.apiKey = apiKey
            }
        }

        // Redirect back.
        return Response.seeOther(URI.create("/users/$id")).build()
    }

    private fun userForm(
        id: Long,
        name: String,
        immichServerUrl: String,
        apiKey: String,
    ): TemplateInstance =
        userDetails
            .instance()
            .data("id", id)
            .data("name", name)
            .data("immichServerUrl", immichServerUrl)
            .data("apiKey", apiKey)
            .data("error", null)
}
