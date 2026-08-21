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
import services.ContentType
import java.util.UUID

@Entity
@Table(name = "assets_staging_area")
data class StagedAsset(
    @Column("user_id")
    val userId: UserId,
    @Id
    @Column(name = "asset_id")
    val assetId: UUID,
    @Column(name = "content_type")
    @Enumerated(EnumType.STRING)
    val contentType: ContentType,
    @Column("current_state")
    @Enumerated(EnumType.STRING)
    var currentState: AssetConversionStates,
) : PanacheEntityBase

enum class AssetConversionStates {
    QUEUED,
    WAITING_DELETION,
    KEEP_AS_IS,
}

@ApplicationScoped
class AssetStagingAreaRepository : PanacheRepositoryBase<StagedAsset, UUID> {
    @Transactional
    fun dropById(id: UUID) = deleteById(id)

    @Transactional
    fun getAll(): List<StagedAsset> = listAll()

    @Transactional
    fun findQueuedByUserId(
        userId: UserId,
        pageIndex: Int,
        pageSize: Int,
        contentTypes: List<ContentType>,
    ): List<StagedAsset> {
        val query =
            buildString {
                append("userId = ?1 and currentState = ?2")
                append(" and contentType in ?3")
            }

        val effectiveContentTypes = contentTypes.ifEmpty { ContentType.entries.toList() }

        return find(query, userId, AssetConversionStates.QUEUED, effectiveContentTypes)
            .page(pageIndex, pageSize)
            .list()
    }

    @Transactional
    fun countQueuedByUserId(
        userId: UserId,
        contentTypes: List<ContentType>,
    ): Long {
        val query =
            buildString {
                append("userId = ?1 and currentState = ?2")
                append(" and contentType in ?3")
            }

        val effectiveContentTypes = contentTypes.ifEmpty { ContentType.entries.toList() }

        return count(query, userId, AssetConversionStates.QUEUED, effectiveContentTypes)
    }
}
