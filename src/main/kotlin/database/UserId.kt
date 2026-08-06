package database

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Wrapper around the user's numeric id. A plain data class (not a Kotlin value class):
 * Hibernate maps it via the [UserIdConverter] below, and value classes don't play well
 * with AttributeConverter/accessor invocation because of JVM name mangling.
 */
data class UserId(
    val value: Long,
) {
    // Single-String constructor so JAX-RS can bind it straight from a @PathParam.
    constructor(value: String) : this(value.toLong())
}

@Converter(autoApply = true)
class UserIdConverter : AttributeConverter<UserId, Long> {
    override fun convertToDatabaseColumn(attribute: UserId?): Long? = attribute?.value

    override fun convertToEntityAttribute(dbData: Long?): UserId? = dbData?.let(::UserId)
}
