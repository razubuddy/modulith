package io.liquidsoftware.common.types

import arrow.core.Nel
import arrow.core.ValidatedNel
import arrow.core.continuations.EffectScope
import arrow.core.invalid
import arrow.core.toNonEmptyListOrNull
import arrow.core.validNel
import io.liquidsoftware.common.validation.MsisdnParser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import org.valiktor.ConstraintViolationException
import org.valiktor.functions.isEmail
import org.valiktor.functions.isNotEmpty
import org.valiktor.functions.isPositiveOrZero
import org.valiktor.functions.isValid
import org.valiktor.functions.matches
import org.valiktor.i18n.mapToMessage
import org.valiktor.validate

@JvmInline
value class ValidationError(val error: String)

@ResponseStatus(code = HttpStatus.PRECONDITION_FAILED)
data class ValidationException(val errors: Nel<ValidationError>) : RuntimeException() {
  val errorString = errors.toErrString()
}

typealias ValidationErrorNel<T> = ValidatedNel<ValidationError, T>

// Helpful extension functions

fun Nel<ValidationError>.toErrStrings() =
  this.map { it.error }.toList()
fun Nel<ValidationError>.toErrString() =
  this.map { it.error }.joinToString { "$it\n" }

// Returns the Validated value OR throws
context(EffectScope<Throwable>)
suspend fun <T:SimpleType<*>> ValidationErrorNel<T>.getOrShift(): T = this.fold(
  { shift(ValidationException(it)) },
  { it }
)

// Can be used as shortcuts to create simple types from Strings
// Note that these throw,
context(EffectScope<Throwable>)
suspend fun String.toNonEmptyString() = NonEmptyString.of(this).getOrShift()
context(EffectScope<Throwable>)
suspend fun String.toEmailAddress() = EmailAddress.of(this).getOrShift()
context(EffectScope<Throwable>)
suspend fun String.toMsisdn() = Msisdn.of(this).getOrShift()
context(EffectScope<Throwable>)
suspend fun String.toPostalCode() = PostalCode.of(this).getOrShift()

abstract class SimpleType<T> {
  abstract val value: T
  override fun toString(): String = value.toString()
}

class NonEmptyString private constructor(override val value: String)
  : SimpleType<String>() {
  companion object {
    fun of(value: String): ValidationErrorNel<NonEmptyString> = ensure {
      validate(NonEmptyString(value)) {
        validate(NonEmptyString::value).isNotEmpty()
      }
    }
  }
}

class PositiveInt private constructor(override val value: Int)
  : SimpleType<Int>() {
  companion object {
    fun of(value: Int): ValidationErrorNel<PositiveInt> = ensure {
      validate(PositiveInt(value)) {
        validate(PositiveInt::value).isPositiveOrZero()
      }
    }
  }
}

class PositiveLong private constructor(override val value: Long)
  : SimpleType<Long>() {
  companion object {
    fun of(value: Long): ValidationErrorNel<PositiveLong> = ensure {
      validate(PositiveLong(value)) {
        validate(PositiveLong::value).isPositiveOrZero()
      }
    }
  }
}

class EmailAddress private constructor(override val value: String): SimpleType<String>() {
  companion object {
    fun of(value: String): ValidationErrorNel<EmailAddress> = ensure {
      validate(EmailAddress(value)) {
        validate(EmailAddress::value).isNotEmpty()
        validate(EmailAddress::value).isEmail()
      }
    }
  }
}

class PostalCode private constructor(override val value: String): SimpleType<String>() {
  companion object {
    fun of(value: String): ValidationErrorNel<PostalCode> = ensure {
      validate(PostalCode(value)) {
        validate(PostalCode::value).matches("""\d{5}""".toRegex())
      }
    }
  }
}

class Msisdn private constructor(override val value: String): SimpleType<String>() {
  companion object {
    fun of(value: String): ValidationErrorNel< Msisdn> = ensure {
      val msisdn = validate(Msisdn(value)) {
        validate(Msisdn::value).isValid { MsisdnParser.isValid(it) }
      }
      Msisdn(MsisdnParser.toInternational(msisdn.value))
    }
  }
}

inline fun <reified T> ensure(ensureFn: () -> T): ValidationErrorNel<T> = try {
  ensureFn().validNel()
} catch (ex: ConstraintViolationException) {
  ex
    .constraintViolations
    .mapToMessage()
    .map { "'${it.value}' of ${T::class.simpleName}.${it.property}: ${it.message}" }
    .map { ValidationError(it) }
    .let { it.toNonEmptyListOrNull()!! }
    .invalid()
}
