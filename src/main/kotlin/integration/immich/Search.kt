@file:UseSerializers(UUIDSerializer::class)

package integration.immich

import internal.serdes.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID
import kotlin.time.Duration

@Serializable
data class SearchAssetsRequest(
    val city: String? = null,
    val country: String? = null,
    val createdAfter: String? = null, // Formatted as ISO 8601 string
    val isFavorite: Boolean? = null,
    val isOffline: Boolean? = null,
    val make: String? = null,
    val model: String? = null,
    val withExif: Boolean? = null
)


@Serializable
data class SearchAssetsResponse(
    //val albums: , // SearchAlbumResponseDto
    val assets: SearchAssetResponseDto
)

// 2. The inner container holding the array list
@Serializable
data class SearchAssetResponseDto(
    val count: Int,
    //val facets
    val items: List<AssetResponseDto>,
    val nextPage: String? = null,
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
    val originalFileName: String,
    val originalPath: String? = null,
    val ownerId: UUID,
    val width: Int? = null,
)
