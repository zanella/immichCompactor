@file:UseSerializers(UUIDSerializer::class)

package integration.immich

import internal.serdes.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

@Serializable
data class SearchAssetsRequest(
    val cursor: String? = null, // // Since 3.2.0
    val filter: SearchFilter? = null, // Since 3.2.0
) {
    companion object {
        @Serializable
        data class SearchFilter(
            val createdAt: DateFilterNullable? = null,
            val tagIds: IdsFilter? = null,
            val trashedAt: DateFilterNullable? = null,
        ) {
            companion object {
                @Serializable
                data class IdsFilter(
                    val all: List<UUID>? = null,
                    val any: List<UUID>? = null,
                    val none: List<UUID>? = null,
                )
            }
        }

        @Serializable
        data class DateFilterNullable(
            val eq: String? = null,
            val gt: String? = null,
            val gte: String? = null,
            val lt: String? = null,
            val lte: String? = null,
            val ne: String? = null,
        )
    }
}

@Serializable
data class SearchAssetsResponse(
    // val albums: , // SearchAlbumResponseDto
    val assets: SearchAssetResponseDto,
)

// 2. The inner container holding the array list
@Serializable
data class SearchAssetResponseDto(
    val count: Int,
    // val facets
    val items: List<AssetResponseDto>,
    val nextCursor: String? = null,
    val total: Int,
)

@Serializable
data class AssetResponseDto(
    val checksum: String,
    val createdAt: String, // DateTime
    val duplicateId: UUID? = null,
    val duration: Int? = null,
    // val exifInfo
    val fileCreatedAt: String, // DateTime
    val fileModifiedAt: String, // DateTime
    val hasMetadata: Boolean,
    val height: Int? = null,
    val id: UUID,
    val isArchived: Boolean,
    val isEdited: Boolean,
    val isFavorite: Boolean,
    val isOffline: Boolean,
    val isTrashed: Boolean,
    val libraryId: UUID? = null,
    val livePhotoVideoId: UUID? = null,
    val originalFileName: String,
    val originalMimeType: String? = null,
    val originalPath: String? = null,
    val ownerId: UUID,
    val tags: List<TagResponseDto> = emptyList(),
    val type: String? = null,
    val width: Int? = null,
)
