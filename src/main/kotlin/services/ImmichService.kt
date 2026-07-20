package services

import integration.ImmichClient
import integration.ImmichLoggingFilter
import integration.SearchAssetsRequest
import integration.SearchMetadataResponse
import jakarta.enterprise.context.ApplicationScoped
import kotlinx.serialization.json.Json
import org.eclipse.microprofile.rest.client.RestClientBuilder
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit

@ApplicationScoped
class ImmichService {

    val client: ImmichClient by lazy {
        val dynamicBaseUrl = "http://localhost:2283"

        // 1. Configure Json configuration to drop null fields completely
        val customJson = Json {
            //ignoreUnknownKeys = true
            explicitNulls = false // <--- THIS OMITTS NULL FIELDS INSTEAD OF SENDING "null"
        }

        RestClientBuilder.newBuilder()
            .baseUri(URI.create(dynamicBaseUrl))
            //.property("quarkus.kotlin-serialization.json", customJson)
            // 1. Enable built-in network logging scope
            .property("quarkus.rest-client.logging.scope", "request-response")
            // 2. Set max body log limits in characters
            .property("quarkus.rest-client.logging.body-limit", "50000")
            .register(ImmichLoggingFilter::class.java)
            .build(ImmichClient::class.java)
    }

    fun findRecentAssets(apiKey: String): SearchMetadataResponse {

        // Generate an ISO 8601 timestamp string for exactly 30 days ago
        val thirtyDaysAgoIsoString = Instant.now()
            .minus(30, ChronoUnit.DAYS)
            .toString()

        val searchCriteria = SearchAssetsRequest(
            createdAfter = thirtyDaysAgoIsoString,
        )

        return client.searchByMetadata(apiKey, searchCriteria)
    }
}