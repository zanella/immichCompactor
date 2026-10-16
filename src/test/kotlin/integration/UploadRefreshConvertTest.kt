package integration

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusIntegrationTest
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@QuarkusIntegrationTest
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UploadRefreshConvertTest {
    @TestHTTPResource
    lateinit var appUrl: URL

    private val immichBaseUrl = ImmichTestEnvironment.baseUrl
    private val immichApiKey = ImmichTestEnvironment.apiKey

    companion object {
        private var uploadedAssetId: UUID? = null
        private var replacementAssetId: UUID? = null
        private var userId: Int? = null
    }

    @Test
    @Order(1)
    fun `upload screenshot to Immich`() {
        val screenshotFile =
            File("src/test/resources/assets/first_screenshot.png")
                .also { require(it.exists()) { "Test asset not found: ${it.absolutePath}" } }

        val boundary = "----IntegrationTestBoundary${System.currentTimeMillis()}"
        val body = buildMultipartBody(screenshotFile, boundary)

        val url = URI.create("$immichBaseUrl/api/assets").toURL()
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("x-api-key", immichApiKey)
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.doOutput = true
        connection.outputStream.use { it.write(body) }

        val responseCode = connection.responseCode
        val responseBody = connection.inputStream.bufferedReader().readText()

        assertEquals(201, responseCode, "Upload failed: $responseBody")

        val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").find(responseBody)
        uploadedAssetId =
            checkNotNull(idMatch?.groupValues?.get(1)?.let(UUID::fromString)) {
                "Could not extract asset id"
            }
    }

    @Test
    @Order(2)
    fun `register user and trigger refresh`() {
        val registerBody =
            """{"name":"Test User","immichServerUrl":"$immichBaseUrl","apiKey":"$immichApiKey"}"""

        val registerUrl = URI.create("$appUrl/api/users").toURL()
        val registerConnection = registerUrl.openConnection() as HttpURLConnection
        registerConnection.requestMethod = "POST"
        registerConnection.setRequestProperty("Content-Type", "application/json")
        registerConnection.doOutput = true
        registerConnection.outputStream.use { it.write(registerBody.toByteArray()) }

        val registerCode = registerConnection.responseCode
        val registerResponseBody = registerConnection.inputStream.bufferedReader().readText()

        assertEquals(200, registerCode, "User registration failed: $registerResponseBody")

        userId = registerResponseBody.trim().toIntOrNull()
        assertNotNull(userId, "Expected user ID in response body, got: $registerResponseBody")

        val refreshUrl = URI.create("$appUrl/api/users/$userId/assets/refresh").toURL()
        val refreshConnection = refreshUrl.openConnection() as HttpURLConnection
        refreshConnection.requestMethod = "POST"
        val refreshCode = refreshConnection.responseCode
        val refreshBody = refreshConnection.inputStream.bufferedReader().readText()

        assertEquals(200, refreshCode, "Refresh failed: $refreshBody")

        repeat(30) {
            val statusUrl = URI.create("$appUrl/api/users/$userId/assets/status").toURL()
            val statusConnection = statusUrl.openConnection() as HttpURLConnection
            statusConnection.requestMethod = "GET"
            val statusBody = statusConnection.inputStream.bufferedReader().readText()

            if (statusBody.contains("\"DONE\"") || statusBody.contains("\"FAILED\"")) {
                assertTrue(statusBody.contains("\"DONE\""), "Refresh job failed: $statusBody")
                return@repeat
            }

            Thread.sleep(2000)
        }
    }

    @Test
    @Order(3)
    fun `assert one IMAGE entry in DB after refresh`() {
        val queuedUrl = URI.create("$appUrl/api/users/$userId/assets/queued").toURL()
        val queuedConnection = queuedUrl.openConnection() as HttpURLConnection
        queuedConnection.requestMethod = "GET"
        val queuedBody = queuedConnection.inputStream.bufferedReader().readText()

        assertTrue(queuedBody.contains("\"total\":1"), "Expected exactly 1 queued asset, got: $queuedBody")
        assertTrue(queuedBody.contains("\"IMAGE\""), "Expected IMAGE content type, got: $queuedBody")
        assertTrue(queuedBody.contains("\"QUEUED\""), "Expected QUEUED state, got: $queuedBody")
    }

    @Test
    @Order(4)
    fun `convert the asset`() {
        val assetId = checkNotNull(uploadedAssetId) { "No uploaded asset id" }

        val convertUrl = URI.create("$appUrl/api/users/$userId/assets/$assetId/convert").toURL()
        val convertConnection = convertUrl.openConnection() as HttpURLConnection
        convertConnection.requestMethod = "POST"

        val convertCode = convertConnection.responseCode
        val convertBody = convertConnection.inputStream.bufferedReader().readText()

        assertEquals(200, convertCode, "Convert failed: $convertBody")
        assertTrue(convertBody.contains("\"converted\""), "Expected conversion to succeed, got: $convertBody")

        val newAssetIdMatch = Regex(""""newAssetId"\s*:\s*"([^"]+)"""").find(convertBody)
        replacementAssetId = newAssetIdMatch?.groupValues?.get(1)?.let(UUID::fromString)
        assertNotNull(replacementAssetId, "Expected a replacement asset id in: $convertBody")
    }

    @Test
    @Order(5)
    fun `original asset is trashed and replacement is in timeline`() {
        val originalId = checkNotNull(uploadedAssetId)
        val newId = checkNotNull(replacementAssetId)

        val originalUrl = URI.create("$immichBaseUrl/api/assets/$originalId").toURL()
        val originalConnection = originalUrl.openConnection() as HttpURLConnection
        originalConnection.setRequestProperty("x-api-key", immichApiKey)
        originalConnection.requestMethod = "GET"

        val originalCode = originalConnection.responseCode
        val originalBody = originalConnection.inputStream.bufferedReader().readText()

        assertEquals(200, originalCode, "Could not fetch original asset: $originalBody")
        assertTrue(originalBody.contains("\"isTrashed\":true"), "Original asset should be trashed: $originalBody")

        val newUrl = URI.create("$immichBaseUrl/api/assets/$newId").toURL()
        val newConnection = newUrl.openConnection() as HttpURLConnection
        newConnection.setRequestProperty("x-api-key", immichApiKey)
        newConnection.requestMethod = "GET"

        val newCode = newConnection.responseCode
        val newBody = newConnection.inputStream.bufferedReader().readText()

        assertEquals(200, newCode, "Could not fetch replacement asset: $newBody")
        assertFalse(newBody.contains("\"isTrashed\":true"), "Replacement should not be trashed: $newBody")
    }

    private fun buildMultipartBody(
        file: File,
        boundary: String,
    ): ByteArray {
        val baos = ByteArrayOutputStream()

        baos.write("--$boundary\r\n".toByteArray())
        baos.write("Content-Disposition: form-data; name=\"assetData\"; filename=\"${file.name}\"\r\n".toByteArray())
        baos.write("Content-Type: image/png\r\n\r\n".toByteArray())
        file.inputStream().use { it.copyTo(baos) }
        baos.write("\r\n".toByteArray())

        listOf(
            "deviceAssetId" to "test-device-asset-id",
            "deviceId" to "test-device",
            "fileCreatedAt" to "2025-01-01T00:00:00.000Z",
            "fileModifiedAt" to "2025-01-01T00:00:00.000Z",
        ).forEach { (name, value) ->
            baos.write("--$boundary\r\n".toByteArray())
            baos.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            baos.write("$value\r\n".toByteArray())
        }

        baos.write("--$boundary--\r\n".toByteArray())

        return baos.toByteArray()
    }
}
