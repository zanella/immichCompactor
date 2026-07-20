package internal.config

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import kotlinx.serialization.json.Json

@ApplicationScoped
class KotlinSerializationConfig {

    @Produces
    @ApplicationScoped
    fun customJson(): Json {
        return Json {
            // TODO: turn to false
            ignoreUnknownKeys = true
            explicitNulls = false // Forces omitting null fields globally
        }
    }
}
