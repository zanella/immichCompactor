package services

import database.UserId
import java.util.concurrent.ConcurrentHashMap

val perUserLimit: MutableMap<UserId, Any> = ConcurrentHashMap()

inline fun <reified T> perUserLimit(
    userId: UserId,
    block: () -> T,
): T =
    perUserLimit
        .getOrPut(userId) { Any() }
        .let {
            synchronized(it) {
                block()
            }
        }
