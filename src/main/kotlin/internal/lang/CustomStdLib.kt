package internal.lang

import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the block and wraps exceptions in a Result,
 * but allows fatal Errors (like OutOfMemoryError) to propagate.
 *
 * Coroutines need to have their exception flow freely.
 */
inline fun <R> runCatchingSafely(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e // Let the coroutine cancel properly
    } catch (e: Exception) {
        Result.failure(e) // Catch normal exceptions
    }

/**
 * Unwraps the Result. If it's a failure, passes the Exception to the block.
 * If the failure was actually a fatal JVM Error, it rethrows it immediately.
 */
inline fun <R, T : R> Result<T>.getOrElseException(onFailure: (exception: Exception) -> R): R =
    when (val throwable = exceptionOrNull()) {
        null -> this.getOrThrow()
        !is Exception -> throw throwable // Rethrow fatal JVM Errors!
        else -> onFailure(throwable) // Pass safe Exceptions to your block
    }
