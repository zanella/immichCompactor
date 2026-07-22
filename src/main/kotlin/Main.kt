import integration.immich.AssetMediaStatus
import integration.immich.CopyAssetRequest
import io.quarkus.runtime.QuarkusApplication
import io.quarkus.runtime.annotations.QuarkusMain
import jakarta.inject.Inject
import services.HandledContentType
import services.HandledContentType.UNKNOWN
import services.HandledContentType.IMAGE_JPEG
import services.HandledContentType.IMAGE_PNG
import services.ImmichService
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.Boolean
import kotlin.system.exitProcess

data class Config (
    val dbUserName: String,
    val dbPassword: String,
    val dbHost: String,
    val dbPort: Int,
    val dbOwnDbName: String,

    val immichServerUrl: String,
)

// TODO: entity
data class UserInfo (
    // val id: Int,
    val name: String,
    val apiKey: String,
)

@QuarkusMain
class Main : QuarkusApplication {

    @Inject
    lateinit var immichService: ImmichService

    private val defaultConfig = Config(
        dbUserName = "postgres",
        dbPassword = "q1w2e3",
        dbHost = "localhost",
        dbPort = 5445,
        dbOwnDbName = "immich_compactor",

        immichServerUrl = "http://localhost:2283",
    )

    private val defaultUserInfo = UserInfo (
        name = "dev_sandbox",
        apiKey = "4kIF0A1ObDCEf5Znluug6nWXcsncXlPkk1jGH0nRF8"
    )

    /**
     * TODO Gradle:
     *
     *  - ktlint
     *
     * TODO DB:
     *
     *  - migrations
     *  - docker volume
     *
     */
    override fun run(vararg args: String?): Int {
        val tmpDir = Files.createTempDirectory("immich-upload-").toFile()
            .also { it.deleteOnExit() }

        // If this goes through, enough to see the server is up and we can auth

        /* require(immichService.client.authValidateToken(defaultUserInfo.apiKey).authStatus) {
            "The API key is invalid."
        } */

        val searchResponse = immichService.findRecentAssets(defaultUserInfo.apiKey)

        // TODO: save new entries, filter out: already converted, trashed

        searchResponse.assets.items.forEach { asset ->

            val (_, suffix) = splitFileName(asset.originalFileName).also { require(it.second.isNotBlank()) }

            val downloadFd = File(tmpDir, "originalDownloaded.$suffix")

            val (mediaType, fileType) = immichService.downloadAssetToDisk(
                apiKey = defaultUserInfo.apiKey,
                assetId = UUID.fromString("d2c65f57-5153-42a1-a0bb-91c821be63c6"),
                targetOutputFile = downloadFd
            )

            // Pass through converter
            // TODO: create a file without the random part

            val convertedFd = convertAsset(fileType, downloadFd, asset.originalFileName, tmpDir)

            // TODO: move to finally
            downloadFd.delete()

            //
            val uploadResponse = immichService.uploadLocalFile(
                defaultUserInfo.apiKey,
                asset,
                convertedFd,
                mediaType,
            ).also {
                require(it.status == AssetMediaStatus.created) {
                    "Immich said ${convertedFd.name} is a duplicate"
                }
            }

            // TODO: save step to DB

            // TODO: move to finally
            convertedFd.delete()

            // Transfer metadata
            immichService.client.copyAsset(defaultUserInfo.apiKey,
                CopyAssetRequest(
                    albums = true,
                    favorite = true,
                    sharedLinks = true,
                    sidecar = true,
                    sourceId = asset.id,
                    stack = true,
                    targetId = uploadResponse.id
                )
            )

            // TODO: save step to DB

            // TODO: Delete original

            // TODO: Delete from DB
        }

        return 0
    }

    fun convertAsset(
        fileType: HandledContentType,
        input: File,
        originalName: String,
        temporaryDir: File,
    ): File = when (fileType) {
        UNKNOWN -> TODO()
        IMAGE_JPEG, IMAGE_PNG -> {
            convertImage(input, originalName, temporaryDir)
        }
    }

    fun convertImage(
        input: File,
        originalName: String,
        temporaryDir: File,
    ): File {
        val convertedFd = File(temporaryDir,
            splitFileName(originalName).also { require(it.first.isNotBlank()) }.first + ".jxl")

        // 1. Build the exact command string using Kotlin variables
        val command = "cjxl --lossless_jpeg=0 -q 75 ${input.absolutePath} '${convertedFd.absolutePath}'"

        // 2. Pass the command execution to the system shell (sh/bash)
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()

        // 3. Wait for the command to finish and get the exit code
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            println("Success: Image converted to JXL.")
        } else {
            val errorResult = process.inputStream.bufferedReader().readText()

            println("Failed with exit code $exitCode. Error: $errorResult")

            // TODO: mark item as failure, NOT exit
            exitProcess(exitCode)
        }

        return convertedFd
    }

    private fun splitFileName(fileName: String): Pair<String, String> =
        // If there is no dot, the entire string is the base name, and the suffix is empty
        if (!fileName.contains(".")) {
             Pair(fileName, "")
        } else {
            Pair(fileName.substringBeforeLast("."), fileName.substringAfterLast(".").lowercase().trim())
        }

}