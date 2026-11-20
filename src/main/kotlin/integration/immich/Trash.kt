package integration.immich

import kotlinx.serialization.Serializable

@Serializable
data class EmptyTrashResponseDto(
    val count: Int,
)

@Serializable
data class RestoreTrashResponseDto(
    val count: Int,
)
