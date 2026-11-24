package database

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import services.ContentType
import java.util.UUID

typealias AssetId = UUID

@Entity
@Table(name = "assets_staging_area")
data class StagedAsset(
    @Column("user_id")
    val userId: UserId,
    @Id
    @Column(name = "asset_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val assetId: AssetId,
    @Column(name = "content_type")
    @Enumerated(EnumType.STRING)
    val contentType: ContentType,
    @Column("current_state")
    @Enumerated(EnumType.STRING)
    var currentState: AssetConversionState,
    @Column("replacement_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    var replacementId: AssetId? = null,
    /*
     The IMAGE asset will have this populated.
     Allows the listing page to filter out the videos which are linked as livePhoto*Videos*
     */
    @Column("live_photo_video_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    var livePhotoVideoId: AssetId? = null,
    // The livePhotoVideo ITSELF will have this populated when its replacement has been uploaded
    @Column("replace_live_photo_video_id_of")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    var replaceLivePhotoVideoIdOf: AssetId? = null,
) : PanacheEntityBase

enum class AssetConversionState {
    QUEUED,
    REPLACEMENT_HAS_BEEN_UPLOADED,
    REPLACEMENT_HAS_BEEN_UPDATED_WITH_METADATA,
    WAITING_DELETION,

    // Terminal state
    KEEP_AS_IS,
}

@ApplicationScoped
class AssetStagingAreaRepository : PanacheRepositoryBase<StagedAsset, UUID> {
    @Transactional
    fun dropById(id: UUID) = deleteById(id)

    @Transactional
    fun getAll(): List<StagedAsset> = listAll()

    /**
     * Base filter shared by [findQueuedByUserId] and [countQueuedByUserId]: the user's
     * QUEUED assets of the given content types. A live photo's video part gets staged
     * as its own row, but it is only ever processed together with its parent image —
     * never standalone — so it is excluded here.
     */
    private val queuedAssetsQuery: String =
        buildString {
            append("userId = ?1 and currentState = ?2")
            append(" and contentType in ?3")
            append(" and assetId not in (")
            append("select s.livePhotoVideoId from StagedAsset s where s.livePhotoVideoId is not null")
            append(")")
        }

    @Transactional
    fun findQueuedByUserId(
        userId: UserId,
        pageIndex: Int,
        pageSize: Int,
        contentTypes: List<ContentType>,
    ): List<StagedAsset> {
        val effectiveContentTypes = contentTypes.ifEmpty { ContentType.entries.toList() }

        return find(queuedAssetsQuery, userId, AssetConversionState.QUEUED, effectiveContentTypes)
            .page(pageIndex, pageSize)
            .list()
    }

    @Transactional
    fun countQueuedByUserId(
        userId: UserId,
        contentTypes: List<ContentType>,
    ): Long {
        val effectiveContentTypes = contentTypes.ifEmpty { ContentType.entries.toList() }

        return count(queuedAssetsQuery, userId, AssetConversionState.QUEUED, effectiveContentTypes)
    }
}
