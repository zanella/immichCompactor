package database

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table

@Entity
@Table(name = "user_info")
data class UserInfo(
    @Column("api_key")
    var apiKey: String,
    @Column
    var name: String,
    @Column("immich_server_url")
    var immichServerUrl: String,
) : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private var _id: Int? = null

    // TODO: return value class
    val id: Int
        get() = _id ?: throw IllegalStateException("Entity is not persisted yet")
}

@ApplicationScoped
class UserInfoRepository : PanacheRepositoryBase<UserInfo, Int>

inline fun <reified E : Any, T : Any> PanacheRepositoryBase<E, T>.getById(
    id: T,
    lockModeType: LockModeType = LockModeType.NONE,
): E =
    findById(id, lockModeType)
        ?: throw EntityNotFoundException("${E::class.simpleName} not found with id: $id")
