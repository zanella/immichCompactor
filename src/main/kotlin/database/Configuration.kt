package database

/* import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "configuration")
data class Configuration (
    @Column
    val url: String,
) : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private var _id: Long? = null

    val id: Long
        get() = _id ?: throw IllegalStateException("Entity is not persisted yet")
}

@ApplicationScoped
class ImmichServerInfoRepository : PanacheRepositoryBase<Configuration, Long> */