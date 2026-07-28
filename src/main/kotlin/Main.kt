import database.AssetConversionStates
import database.AssetStagingAreaRepository
import database.ConvertedAsset
import database.ConvertedAssetStates
import database.ConvertedAssetsRepository
import database.StagedAsset
import database.UserInfo
import database.UserInfoRepository
import integration.immich.AssetMediaStatus
import integration.immich.CopyAssetRequest
import integration.immich.DeleteAssetsRequest
import integration.immich.SearchAssetsResponse
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import services.HandledContentType
import services.HandledContentType.IMAGE_JPEG
import services.HandledContentType.IMAGE_PNG
import services.HandledContentType.UNKNOWN
import services.ImmichService
import java.io.File
import java.nio.file.Files
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
    lateinit var immichService: ImmichService

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

    @Transactional
    fun fakeInitDb(): UserInfo {
        if (userInfoRepository.listAll().isEmpty()) {
            UserInfo(
                apiKey = "H2LzDTSsaJrogKz1T7Z7pWyDQV8UbVUzC0A9JCi9A",
                name = "dev_sandbox",
                immichServerUrl = "http://localhost:2283",
            ).also(userInfoRepository::persist)
        }

        return userInfoRepository.listAll().single().also { userInfo ->
            entityManager.detach(userInfo)
        }
    }

    @Transactional
    fun queueNonConvertedAssets(
        userInfo: UserInfo,
        searchAssetsResponse: SearchAssetsResponse,
    ) {
        val stagedAssetsIds =
            assetStagingAreaRepository
                .listAll()
                // TODO: filter on query
                .filter { it.currentState == AssetConversionStates.QUEUED }
                .map { it.assetId }
                .toSet()

        val convertedAssetsIds =
            convertedAssetsRepository
                .listAll()
                .map { it.assetId }
                .toSet()

        val skippableAssets = stagedAssetsIds + convertedAssetsIds

        searchAssetsResponse.assets.items
            .filter { !skippableAssets.contains(it.id) }
            .filter { !it.isTrashed }
            .forEach { asset ->
                StagedAsset(
                    userId = userInfo.id,
                    assetId = asset.id,
                    currentState = AssetConversionStates.QUEUED,
                ).also(assetStagingAreaRepository::persist)
            }
    }

    fun processQueuedAssets(userInfo: UserInfo) {
        val tmpDir =
            Files
                .createTempDirectory("immich-upload-")
                .toFile()
                .also { it.deleteOnExit() }

        assetStagingAreaRepository.getAll().forEach { stagedAsset ->
            val assetInfo = immichService.client.getAssetInfo(userInfo.apiKey, stagedAsset.assetId)

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
                    userId = userInfo.id,
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

            stagedAsset.currentState = AssetConversionStates.OBSOLETE
            replacementEntity.currentState = ConvertedAssetStates.COMPLETE

            // Send original asset to trash
            immichService.client.deleteAssets(
                userInfo.apiKey,
                DeleteAssetsRequest(ids = listOf(assetInfo.id), force = false),
            )

            assetStagingAreaRepository.dropById(assetInfo.id)
        }
    }

    /*
     * TODO Gradle:
     *  - Compile with GraalVM
     *
     * TODO DB:
     *  - docker volume
     *
     */
    fun run(vararg args: String?): Int {
        val defaultUserInfo = fakeInitDb()

        // If this goes through: the server is up and we can auth
        require(immichService.client.authValidateToken(defaultUserInfo.apiKey).authStatus) {
            "The API key is invalid."
        }

        // TODO: handle assets left in between

        queueNonConvertedAssets(defaultUserInfo, immichService.findRecentAssets(defaultUserInfo.apiKey))

        processQueuedAssets(defaultUserInfo)

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
