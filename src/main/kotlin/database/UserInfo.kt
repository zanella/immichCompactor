@file:UseSerializers(UUIDSerializer::class)

package database

import internal.serdes.UUIDSerializer
import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import io.quarkus.runtime.annotations.RegisterForReflection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Transient
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

@Entity
@Table(name = "user_info")
@Serializable
@RegisterForReflection
data class UserInfo(
    @Column("api_key")
    var apiKey: String,
    @Column
    var name: String,
    @Column("immich_server_url")
    var immichServerUrl: String,
    @Column("tag_uuid")
    private val _tagUUID: String,
) : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private var _id: Int? = null

    @get:Transient
    val id: UserId
        get() = _id?.let(::UserId) ?: throw IllegalStateException("Entity is not persisted yet")

    @get:Transient
    val tagId: UUID
        get() = UUID.fromString(_tagUUID)
}

@ApplicationScoped
class UserInfoRepository : PanacheRepositoryBase<UserInfo, Int>

inline fun <reified E : Any, T : Any> PanacheRepositoryBase<E, T>.getById(
    id: T,
    lockModeType: LockModeType = LockModeType.NONE,
): E =
    findById(id, lockModeType)
        ?: throw EntityNotFoundException("${E::class.simpleName} not found with id: $id")
