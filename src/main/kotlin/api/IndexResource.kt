package api

import io.quarkus.qute.Location
import io.quarkus.qute.Template
import io.quarkus.qute.TemplateInstance
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/")
class IndexResource(
    @param:Location("index.html")
    private val index: Template,
) {
    @GET
    @Produces(MediaType.TEXT_HTML)
    fun getHome(): TemplateInstance = index.instance()
}
