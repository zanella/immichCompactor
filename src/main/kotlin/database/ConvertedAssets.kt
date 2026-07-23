package database

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import java.util.UUID

@Entity
@Table(name = "converted_assets")
data class ConvertedAsset (
    @Column("user_id")
    val userId: Long,

    @Column("asset_id")
    val assetId: UUID,

    @Column("current_state")
    @Enumerated(EnumType.STRING)
    val currentState: ConvertedAssetStates
)

enum class ConvertedAssetStates {
    UPLOADED,
    REPLACED,
}

@ApplicationScoped
class ConvertedAssetsRepository(
    private val entityManager: EntityManager,
) : PanacheRepositoryBase<ConvertedAsset, UUID> {
    @Transactional
    fun store(entity: ConvertedAsset) = persist(entity)

    @Transactional
    fun getAllDetached(): List<ConvertedAsset> = listAll().also(entityManager::detach)
}