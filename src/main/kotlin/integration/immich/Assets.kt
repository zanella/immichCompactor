package integration.immich

import jakarta.ws.rs.FormParam
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.PartFilename
import org.jboss.resteasy.reactive.PartType
import java.io.InputStream

// TODO: data class
class AssetMultipartUpload {

    @FormParam("assetData")
    @PartType(MediaType.APPLICATION_OCTET_STREAM)
    @PartFilename("fileNamePlaceholder") // Will be overridden dynamically at runtime
    lateinit var assetData: InputStream

    @FormParam("deviceId")
    @PartType(MediaType.TEXT_PLAIN)
    lateinit var deviceId: String

    @FormParam("deviceAssetId")
    @PartType(MediaType.TEXT_PLAIN)
    lateinit var deviceAssetId: String

    @FormParam("fileCreatedAt")
    @PartType(MediaType.TEXT_PLAIN)
    lateinit var fileCreatedAt: String

    @FormParam("fileModifiedAt")
    @PartType(MediaType.TEXT_PLAIN)
    lateinit var fileModifiedAt: String
}
