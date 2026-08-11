@file:UseSerializers(UUIDSerializer::class)

package integration.immich

import internal.serdes.UUIDSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

@Serializable
data class TagResponseDto(
    @SerialName("id")
    val id: UUID,
    @SerialName("name")
    val name: String,
    @SerialName("value")
    val value: String, // Represents the full path of the tag
    @SerialName("createdAt")
    val createdAt: String, // ISO-8601 DateTime string
    @SerialName("updatedAt")
    val updatedAt: String, // ISO-8601 DateTime string
    @SerialName("color")
    val color: String? = null, // Tag color representation (hex code format)
    @SerialName("parentId")
    val parentId: String? = null, // Optional parent tag ID for nested tagging structures
)

// //////////////////////////////////////////////

@Serializable
data class CreateTagRequest(
    @SerialName("name")
    val name: String,
    @SerialName("color")
    val color: String? = null, // Can accept a hex string or null
    @SerialName("parentId")
    val parentId: UUID? = null,
)

// //////////////////////////////////////////////

/**
 * Payload model for the PUT /tags/assets endpoint.
 * Links a collection of tags to a collection of assets simultaneously.
 */
@Serializable
data class BulkTagAssetsDto(
    @SerialName("assetIds")
    val assetIds: List<UUID>,
    @SerialName("tagIds")
    val tagIds: List<UUID>,
)

@Serializable
data class BulkTagAssetsResponse(
    val count: Int,
)
