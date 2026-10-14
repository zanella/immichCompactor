package integration

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImmichIntegrationTest {
    private val baseUrl = ImmichTestEnvironment.baseUrl

    @Test
    fun `admin user can log in`() {
        val url = URI.create("$baseUrl/api/auth/login").toURL()
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        connection.outputStream.use {
            it.write("""{"email":"admin@test.com","password":"password"}""".toByteArray())
        }

        val responseCode = connection.responseCode
        val responseBody = connection.inputStream.bufferedReader().readText()

        assertEquals(201, responseCode, "Login failed: $responseBody")
        assertTrue(responseBody.contains("accessToken"), "Response should contain an accessToken")
    }
}
