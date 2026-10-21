package integration.immich

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class QueueName {
    @SerialName("thumbnailGeneration")
    THUMBNAIL_GENERATION,

    @SerialName("metadataExtraction")
    METADATA_EXTRACTION,

    @SerialName("videoConversion")
    VIDEO_CONVERSION,

    @SerialName("faceDetection")
    FACE_DETECTION,

    @SerialName("facialRecognition")
    FACIAL_RECOGNITION,

    @SerialName("smartSearch")
    SMART_SEARCH,

    @SerialName("duplicateDetection")
    DUPLICATE_DETECTION,

    @SerialName("backgroundTask")
    BACKGROUND_TASK,

    @SerialName("storageTemplateMigration")
    STORAGE_TEMPLATE_MIGRATION,

    @SerialName("migration")
    MIGRATION,

    @SerialName("search")
    SEARCH,

    @SerialName("sidecar")
    SIDECAR,

    @SerialName("library")
    LIBRARY,

    @SerialName("notifications")
    NOTIFICATIONS,

    @SerialName("backupDatabase")
    BACKUP_DATABASE,

    @SerialName("ocr")
    OCR,

    @SerialName("workflow")
    WORKFLOW,

    @SerialName("integrityCheck")
    INTEGRITY_CHECK,

    @SerialName("editor")
    EDITOR,
}

@Serializable
data class QueueStatisticsDto(
    val active: Int,
    val completed: Int,
    val delayed: Int,
    val failed: Int,
    val paused: Int,
    val waiting: Int,
)

@Serializable
data class QueueResponseDto(
    val isPaused: Boolean,
    val name: QueueName,
    val statistics: QueueStatisticsDto,
)
