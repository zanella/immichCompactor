package database

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "user_info")
data class UserInfo(
    @Column("api_key")
    val apiKey: String,
    @Column
    val name: String,
    @Column("immich_server_url")
    val immichServerUrl: String,
) : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private var _id: Long? = null

    val id: Long
        get() = _id ?: throw IllegalStateException("Entity is not persisted yet")
}

@ApplicationScoped
class UserInfoRepository : PanacheRepositoryBase<UserInfo, Long>

/* inline fun <reified E : Any, T : Any> PanacheRepositoryBase<E, T>.getById(
    id: T,
    lockModeType: LockModeType = NONE
): E =
    findById(id, lockModeType) ?: throw EntityNotFoundException("${E::class.simpleName} not found with id: $id") */
