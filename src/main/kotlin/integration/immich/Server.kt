package integration.immich

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ServerAboutDto(
    // --- Required Properties ---
    @SerialName("licensed")
    val licensed: Boolean,
    @SerialName("version")
    val version: String,
    @SerialName("versionUrl")
    val versionUrl: String,
    // --- Optional Properties ---
    @SerialName("build")
    val build: String? = null,
    @SerialName("buildImage")
    val buildImage: String? = null,
    @SerialName("buildImageUrl")
    val buildImageUrl: String? = null,
    @SerialName("buildUrl")
    val buildUrl: String? = null,
    @SerialName("exiftool")
    val exiftool: String? = null,
    @SerialName("ffmpeg")
    val ffmpeg: String? = null,
    @SerialName("imagemagick")
    val imagemagick: String? = null,
    @SerialName("libvips")
    val libvips: String? = null,
    @SerialName("nodejs")
    val nodejs: String? = null,
    @SerialName("repository")
    val repository: String? = null,
    @SerialName("repositoryUrl")
    val repositoryUrl: String? = null,
    @SerialName("sourceCommit")
    val sourceCommit: String? = null,
    @SerialName("sourceRef")
    val sourceRef: String? = null,
    @SerialName("sourceUrl")
    val sourceUrl: String? = null,
    @SerialName("thirdPartyBugFeatureUrl")
    val thirdPartyBugFeatureUrl: String? = null,
    @SerialName("thirdPartyDocumentationUrl")
    val thirdPartyDocumentationUrl: String? = null,
    @SerialName("thirdPartySourceUrl")
    val thirdPartySourceUrl: String? = null,
    @SerialName("thirdPartySupportUrl")
    val thirdPartySupportUrl: String? = null,
)

const val BOGUS = ""
