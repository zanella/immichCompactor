package internal.config

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import kotlinx.serialization.json.Json

@ApplicationScoped
class KotlinSerializationConfig {
    @Produces
    @ApplicationScoped
    fun customJson(): Json =
        Json {
            // TODO: turn to false ?
            ignoreUnknownKeys = true
            // Forces omitting null fields globally
            explicitNulls = false
            // Fixes case mismatches for all enums globally
            decodeEnumsCaseInsensitive = true
        }
}
