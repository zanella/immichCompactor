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
import java.util.UUID

@Entity
@Table(name = "assets_staging_area")
data class StagedAsset(
    @Column("user_id")
    val userId: UserId,
    @Id
    @Column(name = "asset_id")
    val assetId: UUID,
    @Column("current_state")
    @Enumerated(EnumType.STRING)
    var currentState: AssetConversionStates,
) : PanacheEntityBase

enum class AssetConversionStates {
    QUEUED,
    OBSOLETE,
}

@ApplicationScoped
class AssetStagingAreaRepository : PanacheRepositoryBase<StagedAsset, UUID> {
    @Transactional
    fun dropById(id: UUID) = deleteById(id)

    @Transactional
    fun getAll(): List<StagedAsset> = listAll()
}
