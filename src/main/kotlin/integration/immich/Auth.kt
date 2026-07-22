package integration.immich

import kotlinx.serialization.Serializable

@Serializable
data class AuthStatusResponse(
    val expiresAt: String? = null,
    val isElevated: Boolean,
    val password: Boolean,
    val pinCode: Boolean,
    val pinExpiresAt: String? = null,
)

@Serializable
data class AuthValidateTokenResponse(
    val authStatus: Boolean,
)
