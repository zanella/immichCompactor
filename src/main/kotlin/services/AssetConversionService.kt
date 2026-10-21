package services

import database.AssetConversionState
import database.AssetConversionState.KEEP_AS_IS
import database.AssetConversionState.QUEUED
import database.AssetConversionState.REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA
import database.AssetConversionState.REPLACEMENT_HAS_BEEN_UPLOADED
import database.AssetConversionState.WAITING_DELETION
import database.AssetId
import database.AssetStagingAreaRepository
import database.StagedAsset
import database.UserId
import database.UserInfo
import database.UserInfoRepository
import database.getById
import integration.immich.AssetMediaStatus
import integration.immich.BulkTagAssetsDto
import integration.immich.DeleteAssetsRequest
import integration.immich.ImmichClient
import integration.immich.UpdateAssetDto
import internal.lang.CustomEither
import internal.lang.runCatchingSafely
import io.quarkus.logging.Log
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.control.ActivateRequestContext
import jakarta.enterprise.event.Observes
import jakarta.transaction.Transactional
import jakarta.ws.rs.core.MediaType
import services.HandledContentType.UNKNOWN
import services.ImmichService.Companion.instantiateClient
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.system.exitProcess

@ApplicationScoped
class AssetConversionService(
    private val assetStagingAreaRepository: AssetStagingAreaRepository,
    private val immichService: ImmichService,
    private val userInfoRepository: UserInfoRepository,
) {
    private lateinit var temporaryWorkingDirectory: File

    val underTest: UnderTest = UnderTest()

    @PostConstruct
    fun postConstructImpl() {
        temporaryWorkingDirectory =
            Files
                .createTempDirectory("immich-upload-")
                .toFile()
                .also { it.deleteOnExit() }
    }

    fun onResume(
        @Observes event: StartupEvent,
    ) {
        val stuckAssets =
            QuarkusTransaction
                .requiringNew()
                .call {
                    assetStagingAreaRepository
                        .find("currentState != ?1 and currentState != ?2", QUEUED, KEEP_AS_IS)
                        .list()
                        .map { it.userId to it.assetId }
                }

        if (stuckAssets.isEmpty()) return

        Log.info("Resuming ${stuckAssets.size} stuck asset(s) on startup")

        Thread.ofVirtual().start {
            stuckAssets.forEach { (userId, assetId) ->
                runCatchingSafely { processQueuedAsset(userId, assetId) }
                    .onFailure { e -> Log.error("Failed to resume asset $assetId: ${e.message}", e) }
            }
        }
    }

    @ActivateRequestContext // Spawns a temporary CDI Request Context for this background thread
    fun processQueuedAsset(
        userId: UserId,
        queuedAssetId: UUID,
        queuedAssetsContentTypesParam: Set<ContentType> = ContentType.entries.toSet(),
    ): CustomEither<Pair<UserInfo, UUID>, String> =
        perUserLimit(userId) {
            val queuedAssetsContentTypes =
                queuedAssetsContentTypesParam
                    .ifEmpty { ContentType.entries.toSet() }

            val ctx =
                userInfoRepository
                    .getById(userId.value)
                    .let { it to ImmichClient.instantiateClient(it.immichServerUrl) }
                    .let { Context(client = it.second, userInfo = it.first) }

            var res: CustomEither<Pair<UserInfo, UUID>, String>? = null

            try {
                while (res == null) {
                    val stagedAsset =
                        QuarkusTransaction
                            .joiningExisting()
                            .call {
                                assetStagingAreaRepository
                                    .findById(queuedAssetId)
                                    ?.also { Log.debug("stagedAsset: $it") }
                            }
                            ?: return CustomEither.Right("Asset not found in the Database")

                    Log.debug("Asset current state: ${stagedAsset.currentState}")

                    res =
                        when (stagedAsset.currentState) {
                            QUEUED -> handleQueuedState(ctx, queuedAssetsContentTypes, stagedAsset)
                            REPLACEMENT_HAS_BEEN_UPLOADED -> handleReplacementHasBeenUploaded(ctx, stagedAsset)
                            REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA ->
                                handleReplacementHasBeenUpdatedWithMetadata(ctx, stagedAsset)
                            WAITING_DELETION -> handleWaitingDeletion(ctx, stagedAsset)
                            KEEP_AS_IS -> CustomEither.Right("This asset shouldn't be selectable in the UI...")
                        }

                    if (underTest.quitAfterStep) {
                        return CustomEither
                            .Right("Under test: Leaving after step ${stagedAsset.currentState} has been completed")
                    }
                }

                return res
            } finally {
                ctx.deferredListOfFilesToDelete.filterNotNull().forEach { runCatchingSafely { it.delete() } }
            }
        }

    // /////////////////////////////////////////////////////////////////////////

    private data class Context(
        val client: ImmichClient,
        val deferredListOfFilesToDelete: MutableList<File?> = mutableListOf(),
        val userInfo: UserInfo,
    )

    private fun handleQueuedState(
        ctx: Context,
        queuedAssetsContentTypes: Set<ContentType>,
        stagedAsset: StagedAsset,
    ): CustomEither<Pair<UserInfo, UUID>, String>? =
        with(ctx) {
            val assetInfoResult = immichService.getAssetInfo(userInfo.apiKey, stagedAsset.assetId, client)

            Log.debug("assetInfo: $assetInfoResult")

            val errorMsgForSituationThatDropsTheAssetFromQueue =
                if (assetInfoResult == null) {
                    "The asset was not found in Immich"
                } else if (assetInfoResult.isTrashed) {
                    "The asset has been trashed in Immich"
                } else if (assetInfoResult.tags.any { tagInfo -> tagInfo.id == userInfo.tagId }) {
                    "The asset is already tagged."
                } else {
                    null
                }

            errorMsgForSituationThatDropsTheAssetFromQueue
                ?.also { errorMsg ->
                    QuarkusTransaction
                        .requiringNew()
                        .run { assetStagingAreaRepository.deleteById(stagedAsset.assetId) }

                    return CustomEither.Right(errorMsg)
                }

            val assetInfo = requireNotNull(assetInfoResult)

            if (assetInfo.livePhotoVideoId != null) {
                QuarkusTransaction
                    .requiringNew()
                    .run {
                        assetStagingAreaRepository
                            .getById(assetInfo.livePhotoVideoId)
                            .also { it.replaceLivePhotoVideoIdOf = assetInfo.id }
                    }

                val res =
                    processQueuedAsset(
                        ctx.userInfo.id,
                        assetInfo.livePhotoVideoId,
                        setOf(ContentType.VIDEO),
                    )

                when (res) {
                    is CustomEither.Left -> { /* pass */ }
                    is CustomEither.Right -> return res
                }
            }

            val (_, suffix) =
                splitFileName(assetInfo.originalFileName)
                    .also { require(it.second.isNotBlank()) }

            // ////

            val fileType = getHandledContentTypeFromFileType(suffix)

            if (fileType.contentType !in queuedAssetsContentTypes) {
                return CustomEither.Right("The file type $fileType does not match the queued asset content")
            }

            // Download the asset
            val downloadFd =
                downloadAssetToDisk(assetInfo.id, client, suffix, userInfo)
                    .also(deferredListOfFilesToDelete::add)

            // Convert it
            val conversionResponse =
                convertAsset(fileType, downloadFd, assetInfo.originalFileName, temporaryWorkingDirectory)
                    .also { deferredListOfFilesToDelete.add(it.convertedFile) }

            val convertedFd =
                when (conversionResponse.reason) {
                    AssetConversionReason.OK -> requireNotNull(conversionResponse.convertedFile)
                    AssetConversionReason.ORIGINAL_IS_SMALLER -> {
                        modifyAsset(stagedAsset, KEEP_AS_IS)

                        return CustomEither.Right("The original asset is smaller than the converted one")
                    }
                }

            val mediaType =
                when (fileType) {
                    HandledContentType.UNHANDLED_IMAGE -> TODO()
                    UNKNOWN -> TODO()
                    HandledContentType.IMAGE_TO_JPEG_XL -> MediaType.valueOf("image/jxl")
                    HandledContentType.VIDEO_TO_H265 -> MediaType.valueOf("video/mp4")
                }

            // Upload the converted asset
            immichService
                .uploadLocalFile(userInfo.apiKey, assetInfo, client, convertedFd, mediaType)
                .also {
                    if (it.status != AssetMediaStatus.CREATED) {
                        return CustomEither.Right("Immich said ${convertedFd.name} is a ${it.status}")
                    }

                    modifyAsset(stagedAsset, REPLACEMENT_HAS_BEEN_UPLOADED, it.id)
                }

            return null
        }

    private fun handleReplacementHasBeenUploaded(
        ctx: Context,
        stagedAsset: StagedAsset,
    ): CustomEither<Pair<UserInfo, UUID>, String>? =
        with(ctx) {
            immichService
                .transferMetadata(userInfo.apiKey, client, stagedAsset.assetId, requireNotNull(stagedAsset.replacementId))
                .also { modifyAsset(stagedAsset, REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA) }

            return null
        }

    private fun handleReplacementHasBeenUpdatedWithMetadata(
        ctx: Context,
        stagedAsset: StagedAsset,
    ): CustomEither<Pair<UserInfo, UUID>, String>? =
        with(ctx) {
            client
                .bulkTagAssets(
                    userInfo.apiKey,
                    BulkTagAssetsDto(
                        assetIds = listOf(requireNotNull(stagedAsset.replacementId)),
                        tagIds = listOf(userInfo.tagId),
                    ),
                ).also {
                    if (it.count != 1) {
                        return CustomEither.Right("Immich didn't tag the new asset ${stagedAsset.replacementId}")
                    }
                }

            if (stagedAsset.replaceLivePhotoVideoIdOf != null) {
                val response =
                    ctx.client.updateAsset(
                        ctx.userInfo.apiKey,
                        requireNotNull(stagedAsset.replaceLivePhotoVideoIdOf),
                        UpdateAssetDto(
                            livePhotoVideoId = stagedAsset.replacementId,
                        ),
                    )

                if (response.livePhotoVideoId != stagedAsset.assetId) {
                    return CustomEither
                        .Right(
                            """Immich didn't update the asset ${stagedAsset.replaceLivePhotoVideoIdOf} 
                            livePhotoVideoId to ${stagedAsset.assetId}
                            """.trimMargin(),
                        )
                }
            }

            modifyAsset(stagedAsset, WAITING_DELETION)

            return null
        }

    private fun handleWaitingDeletion(
        ctx: Context,
        stagedAsset: StagedAsset,
    ): CustomEither<Pair<UserInfo, UUID>, String> =
        with(ctx) {
            client.deleteAssets(
                userInfo.apiKey,
                DeleteAssetsRequest(ids = listOf(stagedAsset.assetId), force = false),
            )

            QuarkusTransaction.joiningExisting().call {
                assetStagingAreaRepository.dropById(stagedAsset.assetId)
            }

            return CustomEither.Left(userInfo to requireNotNull(stagedAsset.replacementId))
        }

    // /////////////////////////////////////////////////////////////////////////

    @Transactional
    protected fun modifyAsset(
        stagedAsset: StagedAsset,
        newState: AssetConversionState? = null,
        replacementIdParam: AssetId? = null,
    ) {
        assetStagingAreaRepository.getById(stagedAsset.assetId).run {
            if (newState != null) {
                currentState = newState
            }

            if (replacementIdParam != null) {
                replacementId = replacementIdParam
            }

            assetStagingAreaRepository.flush()
        }
    }

    private fun downloadAssetToDisk(
        assetId: UUID,
        client: ImmichClient,
        suffix: String,
        userInfo: UserInfo,
    ): File {
        val downloadFd = File(temporaryWorkingDirectory, "originalDownloaded.$suffix")

        immichService.downloadAssetToDisk(
            apiKey = userInfo.apiKey,
            assetId = assetId,
            client = client,
            targetOutputFile = downloadFd,
        )

        return downloadFd
    }
}

fun convertAsset(
    fileType: HandledContentType,
    input: File,
    originalName: String,
    temporaryDir: File,
): AssetConversionReturn =
    when (fileType) {
        HandledContentType.UNHANDLED_IMAGE ->
            throw IllegalStateException("Should not happen")
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

    Log.debug("Success: Image converted to JXL.")

    return AssetConversionReturn(convertedFd, AssetConversionReason.OK)
}

private fun executeCmd(command: String) {
    // 2. Pass the command execution to the system shell (sh/bash)
    val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()

    // 3. Wait for the command to finish and get the exit code
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        val errorResult = process.inputStream.bufferedReader().readText()

        Log.error("Failed with exit code $exitCode. Error: $errorResult")

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

        Log.debug("Original: ${input.length()} | converted: ${convertedFd.length()}")

        convertedFd.delete()

        AssetConversionReturn(null, AssetConversionReason.ORIGINAL_IS_SMALLER)
    } else {
        AssetConversionReturn(convertedFd, AssetConversionReason.OK)
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

data class UnderTest(
    var quitAfterStep: Boolean = false,
)
