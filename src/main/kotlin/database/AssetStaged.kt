package database

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import java.util.UUID

@Entity
@Table(name = "assets_staging_area")
data class AssetStaged (
    @Column("user_id")
    val userId: Long,

    @Id
    @Column(name = "asset_id")
    val assetId: UUID,

    @Column("current_state")
    @Enumerated(EnumType.STRING)
    val currentState: AssetConversionStates,
)

enum class AssetConversionStates {
    QUEUED,
    REPLACED,
}

@ApplicationScoped
class AssetStagingAreaRepository(
    private val entityManager: EntityManager,
) : PanacheRepositoryBase<AssetStaged, UUID> {
    @Transactional
    fun getAllDetached(): List<AssetStaged> = listAll().also(entityManager::detach)
}
