package services

import database.AssetConversionStates
import database.AssetStagingAreaRepository
import database.ConvertedAsset
import database.ConvertedAssetStates
import database.ConvertedAssetsRepository
import database.UserId
import database.UserInfo
import integration.immich.AssetMediaStatus
import integration.immich.CopyAssetRequest
import integration.immich.DeleteAssetsRequest
import integration.immich.ImmichClient
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import services.HandledContentType.IMAGE_JPEG
import services.HandledContentType.IMAGE_PNG
import services.HandledContentType.UNKNOWN
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.system.exitProcess

@ApplicationScoped
class AssetConversionService(
    private val assetStagingAreaRepository: AssetStagingAreaRepository,
    private val convertedAssetsRepository: ConvertedAssetsRepository,
    private val immichService: ImmichService,
) {
    /** TODO:
     * fun processQueuedAssets(
     *         client: ImmichClient,
     *         userInfo: UserInfo
     *     ) {
     */

    fun processQueuedAssets(
        client: ImmichClient,
        userInfo: UserInfo,
        queuedAssetsIds: List<UUID>,
    ) {
        // val tagId = immichService.upsertTag(userInfo.apiKey, client).id

        val tmpDir =
            Files
                .createTempDirectory("immich-upload-")
                .toFile()
                .also { it.deleteOnExit() }

        queuedAssetsIds
            // TODO: what if the ID is not found ?
            .mapNotNull(assetStagingAreaRepository::findById)
            .forEach { stagedAsset ->
                val assetInfo = immichService.getAssetInfo(userInfo.apiKey, stagedAsset.assetId, client)

                if ((assetInfo == null) || assetInfo.isTrashed) {
                    QuarkusTransaction.requiringNew().run {
                        assetStagingAreaRepository.deleteById(stagedAsset.assetId)
                    }

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
                        client = client,
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
                        .uploadLocalFile(userInfo.apiKey, assetInfo, client, convertedFd, mediaType)
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
                client.copyAsset(
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

                /*  TODO: Tag the assets that have been converted
                client.bulkTagAssets(
                        userInfo.apiKey,
                        BulkTagAssetsDto(assetIds = listOf(uploadResponse.id), tagIds = listOf(tagId)),
                    ).also {
                        require(it.count == 1) { "Immich didn't tag the new asset ${uploadResponse.id}" }
                    } */

                stagedAsset.currentState = AssetConversionStates.OBSOLETE
                replacementEntity.currentState = ConvertedAssetStates.COMPLETE

                // Send original asset to trash
                client.deleteAssets(
                    userInfo.apiKey,
                    DeleteAssetsRequest(ids = listOf(assetInfo.id), force = false),
                )

                assetStagingAreaRepository.dropById(assetInfo.id)
            }
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
