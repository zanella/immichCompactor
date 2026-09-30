package database

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Wrapper around the user's numeric id. A plain data class (not a Kotlin value class):
 * Hibernate maps it via the [UserIdConverter] below, and value classes don't play well
 * with AttributeConverter/accessor invocation because of JVM name mangling.
 */
data class UserId(
    val value: Int,
)

@Converter(autoApply = true)
class UserIdConverter : AttributeConverter<UserId, Int> {
    override fun convertToDatabaseColumn(attribute: UserId?): Int? = attribute?.value

    override fun convertToEntityAttribute(dbData: Int?): UserId? = dbData?.let(::UserId)
}
