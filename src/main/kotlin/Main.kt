import database.AssetStagingAreaRepository
import database.ConvertedAssetsRepository
import database.UserInfo
import database.UserInfoRepository
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import services.HandledContentType
import services.HandledContentType.IMAGE_JPEG
import services.HandledContentType.IMAGE_PNG
import services.HandledContentType.UNKNOWN
import services.ImmichService
import java.io.File
import kotlin.system.exitProcess

data class Config(
    val dbUserName: String,
    val dbPassword: String,
    val dbHost: String,
    val dbPort: Int,
    val dbOwnDbName: String,
    val immichServerUrl: String,
)

// @QuarkusMain
class Main { // : QuarkusApplication {
    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userInfoRepository: UserInfoRepository

    @Inject
    lateinit var assetStagingAreaRepository: AssetStagingAreaRepository

    @Inject
    lateinit var convertedAssetsRepository: ConvertedAssetsRepository

    private val defaultConfig =
        Config(
            dbUserName = "postgres",
            dbPassword = "q1w2e3",
            dbHost = "localhost",
            dbPort = 5445,
            dbOwnDbName = "immich_compactor",
            immichServerUrl = "http://localhost:2283",
        )

    private val defaultUserInfo =
        UserInfo(
            apiKey = "H2LzDTSsaJrogKz1T7Z7pWyDQV8UbVUzC0A9JCi9A",
            name = "dev_sandbox",
            immichServerUrl = "http://localhost:2283",
        )

    private val immichService = ImmichService(assetStagingAreaRepository, convertedAssetsRepository)
    private val client = immichService.instantiateClient(defaultConfig.immichServerUrl)

    /*fun processQueuedAssets(userInfo: UserInfo) {
        val tmpDir =
            Files
                .createTempDirectory("immich-upload-")
                .toFile()
                .also { it.deleteOnExit() }

        assetStagingAreaRepository.getAll().forEach { stagedAsset ->
            val assetInfo = client.getAssetInfo(userInfo.apiKey, stagedAsset.assetId)

            if (assetInfo.isTrashed) {
                assetStagingAreaRepository.deleteById(stagedAsset.assetId)

                return@forEach
            }

            val (_, suffix) =
                splitFileName(assetInfo.originalFileName)
                    .also { require(it.second.isNotBlank()) }

            // Download the asset
            val downloadFd =
                File(tmpDir, "originalDownloaded.$suffix")
                    // TODO: move to finally
                    .also { it.deleteOnExit() }

            val (mediaType, fileType) =
                immichService.downloadAssetToDisk(
                    apiKey = userInfo.apiKey,
                    assetId = assetInfo.id,
                    targetOutputFile = downloadFd,
                )

            // Convert it
            val convertedFd =
                convertAsset(fileType, downloadFd, assetInfo.originalFileName, tmpDir)
                    // TOOD: move to finally
                    .also { it.deleteOnExit() }

            downloadFd.delete()

            // Upload the converted asset
            val uploadResponse =
                immichService
                    .uploadLocalFile(userInfo.apiKey, assetInfo, convertedFd, mediaType)
                    .also {
                        /*
     * TODO: If the application dies right after here, it won't be CREATED,
     *  what to do then ?
     */
                        require(it.status == AssetMediaStatus.CREATED) {
                            "Immich said ${convertedFd.name} is a ${it.status}"
                        }
                    }

            // Replacement: Save state
            val replacementEntity =
                ConvertedAsset(
                    userId = UserId(userInfo.id),
                    assetId = uploadResponse.id,
                    currentState = ConvertedAssetStates.UPLOADED,
                ).also(convertedAssetsRepository::store)

            convertedFd.delete()

            // Transfer metadata
            immichService.client.copyAsset(
                userInfo.apiKey,
                CopyAssetRequest(
                    albums = true,
                    favorite = true,
                    sharedLinks = true,
                    sidecar = true,
                    sourceId = assetInfo.id,
                    stack = true,
                    targetId = uploadResponse.id,
                ),
            )

            // TODO: https://api.immich.app/endpoints/tags/bulkTagAssets
            // Tag the assets that have been converted

            stagedAsset.currentState = AssetConversionStates.OBSOLETE
            replacementEntity.currentState = ConvertedAssetStates.COMPLETE

            // Send original asset to trash
            immichService.client.deleteAssets(
                userInfo.apiKey,
                DeleteAssetsRequest(ids = listOf(assetInfo.id), force = false),
            )

            assetStagingAreaRepository.dropById(assetInfo.id)
        }
    } * /

    / *
     * TODO Gradle:
     *  - Compile with GraalVM
     *
     * TODO DB:
     *  - docker volume
     *
     */
    fun run(vararg args: String?): Int {
        // TODO: handle assets left in between

        // processQueuedAssets(defaultUserInfo)

        return 0
    }

    fun convertAsset(
        fileType: HandledContentType,
        input: File,
        originalName: String,
        temporaryDir: File,
    ): File =
        when (fileType) {
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
        val convertedFd =
            File(
                temporaryDir,
                splitFileName(originalName).also { require(it.first.isNotBlank()) }.first + ".jxl",
            )

        // 1. Build the exact command string using Kotlin variables
        val command = "cjxl --lossless_jpeg=0 -q 75 ${input.absolutePath} '${convertedFd.absolutePath}'"

        // 2. Pass the command execution to the system shell (sh/bash)
        val process =
            ProcessBuilder("sh", "-c", command)
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
