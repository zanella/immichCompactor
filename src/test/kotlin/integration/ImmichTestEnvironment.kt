package integration

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import java.io.File
import java.net.URI
import java.time.Duration

object ImmichTestEnvironment {
    private val network = Network.newNetwork()

    private const val DB_USERNAME = "postgres"
    private const val DB_PASSWORD = "postgres"
    private const val DB_DATABASE = "immich"
    private const val UPLOAD_DIR = "/tmp/immich-test-upload"
    private const val TEST_DB_PATH = "/tmp/immichCompactorIntegration.db"

    val postgres: GenericContainer<*> =
        GenericContainer("ghcr.io/immich-app/postgres:14-vectorchord0.4.3-pgvectors0.2.0")
            .withNetwork(network)
            .withNetworkAliases("database")
            .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
            .withEnv("POSTGRES_USER", DB_USERNAME)
            .withEnv("POSTGRES_DB", DB_DATABASE)
            .withEnv("POSTGRES_INITDB_ARGS", "--data-checksums")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2))

    val redis: GenericContainer<*> =
        GenericContainer("docker.io/valkey/valkey:9")
            .withNetwork(network)
            .withNetworkAliases("redis")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))

    val immich: GenericContainer<*> =
        GenericContainer("ghcr.io/immich-app/immich-server:release")
            .withNetwork(network)
            .withEnv("DB_HOSTNAME", "database")
            .withEnv("DB_USERNAME", DB_USERNAME)
            .withEnv("DB_PASSWORD", DB_PASSWORD)
            .withEnv("DB_DATABASE_NAME", DB_DATABASE)
            .withEnv("REDIS_HOSTNAME", "redis")
            .withFileSystemBind("/tmp/immich-test-upload", "/data")
            .withExposedPorts(2283)
            .dependsOn(postgres, redis)
            .waitingFor(
                Wait
                    .forLogMessage(".*Immich Microservices is running.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )

    val baseUrl: String
        get() = "http://${immich.host}:${immich.getMappedPort(2283)}"

    var apiKey: String
        private set

    var appUserId: Int
        private set

    init {
        File(UPLOAD_DIR).deleteRecursively()
        postgres.start()
        redis.start()
        immich.start()
        waitForServerAbout()
        setupAdminUser()
        apiKey = createApiKey()
        appUserId = registerUserInApp()

        Runtime.getRuntime().addShutdownHook(
            Thread {
                File(UPLOAD_DIR).deleteRecursively()
                File(TEST_DB_PATH).delete()
            },
        )
    }

    private fun waitForServerAbout() {
        var lastException: Exception? = null

        repeat(30) {
            try {
                val url = URI.create("$baseUrl/api/server/ping").toURL()
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    return
                }
            } catch (e: Exception) {
                lastException = e
            }

            Thread.sleep(2000L)
        }

        throw lastException ?: RuntimeException("Immich /api/server/ping did not respond after retries")
    }

    private fun setupAdminUser() {
        var lastException: Exception? = null

        repeat(15) { attempt ->
            try {
                val url = URI.create("$baseUrl/api/auth/admin-sign-up").toURL()
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 10000

                connection.outputStream.use {
                    it.write("""{"email":"admin@test.com","password":"password","name":"Admin"}""".toByteArray())
                }

                val responseCode = connection.responseCode
                val responseBody = connection.inputStream.bufferedReader().readText()

                if (responseCode == 201) {
                    return
                }

                lastException = RuntimeException("Admin sign-up returned $responseCode: $responseBody")
            } catch (e: Exception) {
                lastException = e
            }

            Thread.sleep(3000L * (attempt + 1))
        }

        throw lastException ?: RuntimeException("Admin sign-up failed after retries")
    }

    private fun createApiKey(): String {
        val accessToken = login()

        val url = URI.create("$baseUrl/api/api-keys").toURL()
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Cookie", "immich_access_token=$accessToken")
        connection.doOutput = true

        connection.outputStream.use {
            it.write("""{"name":"test-api-key","permissions":["all"]}""".toByteArray())
        }

        val responseCode = connection.responseCode
        val responseBody = connection.inputStream.bufferedReader().readText()

        check(responseCode == 201) { "Failed to create API key: $responseCode - $responseBody" }

        val secretMatch = Regex(""""secret"\s*:\s*"([^"]+)"""").find(responseBody)
        return checkNotNull(secretMatch?.groupValues?.get(1)) {
            "Could not extract API key secret from response: $responseBody"
        }
    }

    private fun registerUserInApp(): Int {
        val appUrl = "http://localhost:8081"
        val body = """{"name":"Test User","immichServerUrl":"$baseUrl","apiKey":"$apiKey"}"""

        val url = URI.create("$appUrl/api/users").toURL()
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000

        connection.outputStream.use { it.write(body.toByteArray()) }

        val responseCode = connection.responseCode
        val responseBody = connection.inputStream.bufferedReader().readText()

        check(responseCode == 200) { "User registration failed: $responseCode - $responseBody" }

        return responseBody.trim().toIntOrNull()
            ?: error("Expected user ID in response body, got: $responseBody")
    }

    private fun login(): String {
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

        check(responseCode == 201) { "Login failed: $responseCode - $responseBody" }

        val tokenMatch = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(responseBody)
        return checkNotNull(tokenMatch?.groupValues?.get(1)) {
            "Could not extract accessToken from login response: $responseBody"
        }
    }
}
