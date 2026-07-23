@file:UseSerializers(UUIDSerializer::class)

package integration.immich

import internal.serdes.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

@Serializable
data class AssetMediaResponseDto(
    val id: UUID,
    val status: AssetMediaStatus,
)

enum class AssetMediaStatus {
    CREATED,
    DUPLICATE,
}

@Serializable
data class CopyAssetRequest(
    val albums: Boolean,
    val favorite: Boolean,
    val sharedLinks: Boolean,
    val sidecar: Boolean,
    val sourceId: UUID,
    val stack: Boolean,
    val targetId: UUID,
)

/////////////////////
