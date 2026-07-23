package database

import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepositoryBase
import jakarta.persistence.EntityNotFoundException
import jakarta.persistence.LockModeType
import jakarta.persistence.LockModeType.NONE

inline fun <reified E : Any, T : Any> PanacheRepositoryBase<E, T>.getById(
    id: T,
    lockModeType: LockModeType = NONE
): E =
    findById(id, lockModeType) ?: throw EntityNotFoundException("${E::class.simpleName} not found with id: $id")
