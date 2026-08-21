package services

import database.AssetConversionStates
import database.AssetStagingAreaRepository
import database.ConvertedAsset
import database.ConvertedAssetStates
import database.ConvertedAssetsRepository
import database.UserId
import database.UserInfo
import database.getById
import integration.immich.AssetMediaStatus
import integration.immich.BulkTagAssetsDto
import integration.immich.CopyAssetRequest
import integration.immich.DeleteAssetsRequest
import integration.immich.ImmichClient
import internal.lang.runCatchingSafely
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.MediaType
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
    fun processQueuedAssets(
        client: ImmichClient,
        userInfo: UserInfo,
        queuedAssetsId: Set<UUID>,
        queuedAssetsContentTypes: Set<ContentType>,
    ) {
        /* val stagedAssetsIds = QuarkusTransaction.joiningExisting().call {
            assetStagingAreaRepository
                    .listAll()
                    // TODO: filter on DB query, instead of return
                    .filter { it.currentState == AssetConversionStates.QUEUED }
                    .map { it.assetId }
                    .toSet()
        }*/

        queuedAssetsId.forEach { assetId ->
            processQueuedAsset(client, userInfo, assetId, queuedAssetsContentTypes)
        }
    }

    fun processQueuedAsset(
        client: ImmichClient,
        userInfo: UserInfo,
        queuedAssetId: UUID,
        queuedAssetsContentTypes: Set<ContentType> = ContentType.entries.toSet(),
        // TODO: return actual action, inferring from null is not good
    ): UUID? {
        val deferredListOfFilesToDelete = mutableListOf<File?>()

        try {
            // TODO: what was this tag supposed to do ?
            val tagId = immichService.upsertTag(userInfo.apiKey, client).id

            val tmpDir =
                Files
                    .createTempDirectory("immich-upload-")
                    .toFile()
                    .also { it.deleteOnExit() }

            val stagedAsset =
                QuarkusTransaction.joiningExisting().call {
                    assetStagingAreaRepository.findById(queuedAssetId)
                } ?: return null

            println("stagedAsset: $stagedAsset")

            val assetInfo = immichService.getAssetInfo(userInfo.apiKey, stagedAsset.assetId, client)

            println("assetInfo: $assetInfo")

            // TODO: check if asset has tag

            if ((assetInfo == null) || assetInfo.isTrashed) {
                QuarkusTransaction.requiringNew().run { assetStagingAreaRepository.deleteById(stagedAsset.assetId) }

                return null
            }

            val (_, suffix) = splitFileName(assetInfo.originalFileName).also { require(it.second.isNotBlank()) }

            // ////

            val fileType = getHandledContentTypeFromFileType(suffix)

            if (fileType.contentType !in queuedAssetsContentTypes) {
                // TODO: this breaks the UI,
                return null
            }

            // ////

            // Download the asset
            val downloadFd =
                File(tmpDir, "originalDownloaded.$suffix")
                    .also(deferredListOfFilesToDelete::add)

            immichService.downloadAssetToDisk(
                apiKey = userInfo.apiKey,
                assetId = assetInfo.id,
                client = client,
                targetOutputFile = downloadFd,
            )

            // Convert it
            val conversionResponse =
                convertAsset(fileType, downloadFd, assetInfo.originalFileName, tmpDir)
                    .also { deferredListOfFilesToDelete.add(it.convertedFile) }

            val convertedFd =
                when (conversionResponse.reason) {
                    AssetConversionReason.OK -> requireNotNull(conversionResponse.convertedFile)
                    AssetConversionReason.ORIGINAL_IS_SMALLER -> {
                        QuarkusTransaction.joiningExisting().call {
                            assetStagingAreaRepository
                                .getById(stagedAsset.assetId)
                                .currentState = AssetConversionStates.KEEP_AS_IS
                        }

                        return null
                    }
                }

            // TODO: get from the actual converted file
            val mediaType = MediaType.valueOf("video/mp4")

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

            // TODO: save state: if the service dies pick up from here

            // Replacement: Save state
            val replacementEntity =
                ConvertedAsset(
                    userId = UserId(userInfo.id),
                    assetId = uploadResponse.id,
                    currentState = ConvertedAssetStates.UPLOADED,
                ).also(convertedAssetsRepository::store)

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

            client
                .bulkTagAssets(
                    userInfo.apiKey,
                    BulkTagAssetsDto(assetIds = listOf(uploadResponse.id), tagIds = listOf(tagId)),
                ).also {
                    require(it.count == 1) { "Immich didn't tag the new asset ${uploadResponse.id}" }
                }

            stagedAsset.currentState = AssetConversionStates.WAITING_DELETION
            replacementEntity.currentState = ConvertedAssetStates.COMPLETE

            // TODO: receive option to HARD-delete

            // Send original asset to trash
            client.deleteAssets(
                userInfo.apiKey,
                DeleteAssetsRequest(ids = listOf(assetInfo.id), force = false),
            )

            QuarkusTransaction.joiningExisting().call {
                assetStagingAreaRepository.dropById(assetInfo.id)

                convertedAssetsRepository.dropById(replacementEntity.assetId)
            }

            return uploadResponse.id
        } finally {
            deferredListOfFilesToDelete.filterNotNull().forEach {
                runCatchingSafely {
                    it.delete()
                }
            }
        }
    }

    fun convertAsset(
        fileType: HandledContentType,
        input: File,
        originalName: String,
        temporaryDir: File,
    ): AssetConversionReturn =
        when (fileType) {
            UNKNOWN -> TODO()
            HandledContentType.IMAGE_TO_JPEG_XL -> convertImage(input, originalName, temporaryDir)
            HandledContentType.VIDEO_TO_H265 -> convertVideo(input, originalName, temporaryDir)
        }

    fun convertImage(
        input: File,
        originalName: String,
        temporaryDir: File,
    ): AssetConversionReturn {
        val convertedFd =
            File(
                temporaryDir,
                splitFileName(originalName).also { require(it.first.isNotBlank()) }.first + ".jxl",
            )

        // 1. Build the exact command string using Kotlin variables
        val command = "cjxl --lossless_jpeg=0 -q 75 '${input.absolutePath}' '${convertedFd.absolutePath}'"

        executeCmd(command)

        println("Success: Image converted to JXL.")

        return AssetConversionReturn(convertedFd, AssetConversionReason.OK)
    }

    private fun executeCmd(command: String) {
        // 2. Pass the command execution to the system shell (sh/bash)
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()

        // 3. Wait for the command to finish and get the exit code
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            val errorResult = process.inputStream.bufferedReader().readText()

            println("Failed with exit code $exitCode. Error: $errorResult")

            // TODO: mark item as failure, NOT exit
            exitProcess(exitCode)
        }
    }

    fun convertVideo(
        input: File,
        originalName: String,
        temporaryDir: File,
    ): AssetConversionReturn {
        val convertedFd =
            File(
                temporaryDir,
                splitFileName(originalName).also { require(it.first.isNotBlank()) }.first + ".mp4",
            )

        val handBrakeCmd =
            """
           HandBrakeCLI -i '${input.absolutePath}' -o '${convertedFd.absolutePath}' -e x265 -q 22 -r 30 --vfr --encoder-preset medium --all-audio --all-subtitles
        """.trim()

        executeCmd(handBrakeCmd)

        // If the converted ends up being bigger, use the original
        return if (input.length() <= convertedFd.length()) {
            // TODO: this is rather stupid, change it to mark the original as NON-ACTIONABLE

            println("Original: ${input.length()} | converted: ${convertedFd.length()}")

            convertedFd.delete()

            AssetConversionReturn(null, AssetConversionReason.ORIGINAL_IS_SMALLER)
        } else {
            AssetConversionReturn(convertedFd, AssetConversionReason.OK)
        }
    }
}

fun splitFileName(fileName: String): Pair<String, String> =
    // If there is no dot, the entire string is the base name, and the suffix is empty
    if (!fileName.contains(".")) {
        Pair(fileName, "")
    } else {
        Pair(fileName.substringBeforeLast("."), fileName.substringAfterLast(".").lowercase().trim())
    }

data class AssetConversionReturn(
    val convertedFile: File?,
    val reason: AssetConversionReason,
)

enum class AssetConversionReason {
    OK,
    ORIGINAL_IS_SMALLER,
}
