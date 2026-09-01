package database

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.util.UUID

@Entity
@Table(name = "converted_assets")
data class ConvertedAsset(
    @Column("user_id")
    val userId: UserId,
    @Id
    @Column("asset_id")
    @JdbcTypeCode(SqlTypes.VARCHAR)
    val assetId: UUID,
    @Column("current_state")
    @Enumerated(EnumType.STRING)
    var currentState: ConvertedAssetStates,
) : PanacheEntityBase

enum class ConvertedAssetStates {
    UPLOADED,
    COMPLETE,
}

@ApplicationScoped
class ConvertedAssetsRepository(
    private val entityManager: EntityManager,
) : PanacheRepositoryBase<ConvertedAsset, UUID> {
    @Transactional
    fun dropById(id: UUID) = deleteById(id)

    @Transactional // TODO: return attached entity ?
    fun store(entity: ConvertedAsset) = persist(entity)

    @Transactional
    fun getAllDetached(): List<ConvertedAsset> = listAll().also(entityManager::detach)
}
