/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.types.model

import org.jetbrains.kotlin.types.AbstractTypeCheckerContext
import org.jetbrains.kotlin.types.Variance
import kotlin.collections.ArrayList
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

interface KotlinTypeMarker
interface TypeArgumentMarker
interface TypeConstructorMarker
interface TypeParameterMarker

interface SimpleTypeMarker : KotlinTypeMarker
interface CapturedTypeMarker : SimpleTypeMarker
interface DefinitelyNotNullTypeMarker : SimpleTypeMarker

interface FlexibleTypeMarker : KotlinTypeMarker
interface DynamicTypeMarker : FlexibleTypeMarker
interface RawTypeMarker : FlexibleTypeMarker
interface StubTypeMarker : SimpleTypeMarker

interface TypeArgumentListMarker

interface TypeVariableMarker
interface TypeVariableTypeConstructorMarker : TypeConstructorMarker

interface CapturedTypeConstructorMarker : TypeConstructorMarker

interface IntersectionTypeConstructorMarker : TypeConstructorMarker

interface TypeSubstitutorMarker

interface AnnotationMarker

enum class TypeVariance(val presentation: String) {
    IN("in"),
    OUT("out"),
    INV("");

    override fun toString(): String = presentation
}

fun Variance.convertVariance(): TypeVariance {
    return when (this) {
        Variance.INVARIANT -> TypeVariance.INV
        Variance.IN_VARIANCE -> TypeVariance.IN
        Variance.OUT_VARIANCE -> TypeVariance.OUT
    }
}

interface TypeSystemOptimizationContext {
    /**
     *  @return true is a.arguments == b.arguments, or false if not supported
     */
    fun identicalArguments(a: SimpleTypeMarker, b: SimpleTypeMarker) = false
}

/**
 * Context that allow type-impl agnostic access to common types
 */
interface TypeSystemBuiltInsContext {
    fun nullableNothingType(): SimpleTypeMarker
    fun nullableAnyType(): SimpleTypeMarker
    fun nothingType(): SimpleTypeMarker
    fun anyType(): SimpleTypeMarker
}

/**
 * Context that allow construction of types
 */
interface TypeSystemTypeFactoryContext {
    fun createFlexibleType(lowerBound: SimpleTypeMarker, upperBound: SimpleTypeMarker): KotlinTypeMarker
    fun createSimpleType(
        constructor: TypeConstructorMarker,
        arguments: List<TypeArgumentMarker>,
        nullable: Boolean,
        isExtensionFunction: Boolean = false,
        annotations: List<AnnotationMarker>? = null
    ): SimpleTypeMarker

    fun createTypeArgument(type: KotlinTypeMarker, variance: TypeVariance): TypeArgumentMarker
    fun createStarProjection(typeParameter: TypeParameterMarker): TypeArgumentMarker

    fun createErrorType(debugName: String): SimpleTypeMarker
    fun createErrorTypeWithCustomConstructor(debugName: String, constructor: TypeConstructorMarker): KotlinTypeMarker
}

/**
 * Factory, that constructs [AbstractTypeCheckerContext], which defines type-checker behaviour
 * Implementation is recommended to be [TypeSystemContext]
 */
interface TypeCheckerProviderContext {
    fun newBaseTypeCheckerContext(
        errorTypesEqualToAnything: Boolean,
        stubTypesEqualToAnything: Boolean
    ): AbstractTypeCheckerContext
}

/**
 * Extended type system context, which defines set of operations specific to common super-type calculation
 */
interface TypeSystemCommonSuperTypesContext : TypeSystemContext, TypeSystemTypeFactoryContext, TypeCheckerProviderContext {

    fun KotlinTypeMarker.anySuperTypeConstructor(predicate: (TypeConstructorMarker) -> Boolean) =
        newBaseTypeCheckerContext(errorTypesEqualToAnything = false, stubTypesEqualToAnything = true)
            .anySupertype(
                lowerBoundIfFlexible(),
                { predicate(it.typeConstructor()) },
                { AbstractTypeCheckerContext.SupertypesPolicy.LowerIfFlexible }
            )

    fun KotlinTypeMarker.canHaveUndefinedNullability(): Boolean

    fun SimpleTypeMarker.isExtensionFunction(): Boolean

    fun SimpleTypeMarker.typeDepth(): Int

    fun KotlinTypeMarker.typeDepth(): Int = when (this) {
        is SimpleTypeMarker -> typeDepth()
        is FlexibleTypeMarker -> maxOf(lowerBound().typeDepth(), upperBound().typeDepth())
        else -> error("Type should be simple or flexible: $this")
    }

    fun findCommonIntegerLiteralTypesSuperType(explicitSupertypes: List<SimpleTypeMarker>): SimpleTypeMarker?

    /*
     * Converts error type constructor to error type
     * Used only in FIR
     */
    fun TypeConstructorMarker.toErrorType(): SimpleTypeMarker
}

// This interface is only used to declare that implementing class is supposed to be used as a TypeSystemInferenceExtensionContext component
// Otherwise clash happens during DI container initialization: there are a lot of components that extend TypeSystemInferenceExtensionContext
// but they only has it among supertypes to bring additional receiver into their scopes, i.e. they are not intended to be used as
// component implementation for TypeSystemInferenceExtensionContext
interface TypeSystemInferenceExtensionContextDelegate : TypeSystemInferenceExtensionContext

/**
 * Extended type system context, which defines set of type operations specific for type inference
 */
interface TypeSystemInferenceExtensionContext : TypeSystemContext, TypeSystemBuiltInsContext, TypeSystemCommonSuperTypesContext {
    fun KotlinTypeMarker.contains(predicate: (KotlinTypeMarker) -> Boolean): Boolean

    fun TypeConstructorMarker.isUnitTypeConstructor(): Boolean

    fun TypeConstructorMarker.getApproximatedIntegerLiteralType(): KotlinTypeMarker

    fun TypeConstructorMarker.isCapturedTypeConstructor(): Boolean

    fun TypeConstructorMarker.isTypeParameterTypeConstructor(): Boolean

    fun Collection<KotlinTypeMarker>.singleBestRepresentative(): KotlinTypeMarker?

    fun KotlinTypeMarker.isUnit(): Boolean

    fun KotlinTypeMarker.isBuiltinFunctionalTypeOrSubtype(): Boolean

    fun createCapturedType(
        constructorProjection: TypeArgumentMarker,
        constructorSupertypes: List<KotlinTypeMarker>,
        lowerType: KotlinTypeMarker?,
        captureStatus: CaptureStatus
    ): CapturedTypeMarker

    fun createStubType(typeVariable: TypeVariableMarker): StubTypeMarker


    fun KotlinTypeMarker.removeAnnotations(): KotlinTypeMarker
    fun KotlinTypeMarker.removeExactAnnotation(): KotlinTypeMarker

    fun SimpleTypeMarker.replaceArguments(newArguments: List<TypeArgumentMarker>): SimpleTypeMarker

    fun KotlinTypeMarker.hasExactAnnotation(): Boolean
    fun KotlinTypeMarker.hasNoInferAnnotation(): Boolean

    fun TypeVariableMarker.freshTypeConstructor(): TypeConstructorMarker

    fun CapturedTypeMarker.typeConstructorProjection(): TypeArgumentMarker
    fun CapturedTypeMarker.typeParameter(): TypeParameterMarker?
    fun CapturedTypeMarker.withNotNullProjection(): KotlinTypeMarker

    fun typeSubstitutorByTypeConstructor(map: Map<TypeConstructorMarker, KotlinTypeMarker>): TypeSubstitutorMarker
    fun createEmptySubstitutor(): TypeSubstitutorMarker

    fun TypeSubstitutorMarker.safeSubstitute(type: KotlinTypeMarker): KotlinTypeMarker


    fun TypeVariableMarker.defaultType(): SimpleTypeMarker

    fun createTypeWithAlternativeForIntersectionResult(
        firstCandidate: KotlinTypeMarker,
        secondCandidate: KotlinTypeMarker
    ): KotlinTypeMarker

    fun KotlinTypeMarker.isSpecial(): Boolean

    fun TypeConstructorMarker.isTypeVariable(): Boolean
    fun TypeVariableTypeConstructorMarker.isContainedInInvariantOrContravariantPositions(): Boolean

    fun KotlinTypeMarker.isSignedOrUnsignedNumberType(): Boolean

    fun KotlinTypeMarker.isFunctionOrKFunctionWithAnySuspendability(): Boolean

    fun KotlinTypeMarker.isSuspendFunctionTypeOrSubtype(): Boolean

    fun KotlinTypeMarker.isExtensionFunctionType(): Boolean

    fun KotlinTypeMarker.extractArgumentsForFunctionalTypeOrSubtype(): List<KotlinTypeMarker>

    fun KotlinTypeMarker.getFunctionalTypeFromSupertypes(): KotlinTypeMarker

    fun getFunctionTypeConstructor(parametersNumber: Int, isSuspend: Boolean): TypeConstructorMarker

    fun getKFunctionTypeConstructor(parametersNumber: Int, isSuspend: Boolean): TypeConstructorMarker

    private fun KotlinTypeMarker.extractTypeVariables(to: MutableSet<TypeVariableTypeConstructorMarker>) {
        for (i in 0 until argumentsCount()) {
            val argument = getArgument(i)

            if (argument.isStarProjection()) continue

            val argumentType = argument.getType()
            val argumentTypeConstructor = argumentType.typeConstructor()
            if (argumentTypeConstructor is TypeVariableTypeConstructorMarker) {
                to.add(argumentTypeConstructor)
            } else if (argumentType.argumentsCount() != 0) {
                argumentType.extractTypeVariables(to)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun KotlinTypeMarker.extractTypeVariables() = buildSet { extractTypeVariables(this) }
}


class ArgumentList(initialSize: Int) : ArrayList<TypeArgumentMarker>(initialSize), TypeArgumentListMarker

/**
 * Defines common kotlin type operations with types for abstract types
 */
interface TypeSystemContext : TypeSystemOptimizationContext {
    fun KotlinTypeMarker.asSimpleType(): SimpleTypeMarker?
    fun KotlinTypeMarker.asFlexibleType(): FlexibleTypeMarker?

    fun KotlinTypeMarker.isError(): Boolean
    fun TypeConstructorMarker.isError(): Boolean
    fun KotlinTypeMarker.isUninferredParameter(): Boolean
    fun FlexibleTypeMarker.asDynamicType(): DynamicTypeMarker?

    fun FlexibleTypeMarker.asRawType(): RawTypeMarker?
    fun FlexibleTypeMarker.upperBound(): SimpleTypeMarker

    fun FlexibleTypeMarker.lowerBound(): SimpleTypeMarker
    fun SimpleTypeMarker.asCapturedType(): CapturedTypeMarker?

    fun KotlinTypeMarker.isCapturedType() = asSimpleType()?.asCapturedType() != null

    fun SimpleTypeMarker.asDefinitelyNotNullType(): DefinitelyNotNullTypeMarker?
    fun DefinitelyNotNullTypeMarker.original(): SimpleTypeMarker
    fun KotlinTypeMarker.makeDefinitelyNotNullOrNotNull(): KotlinTypeMarker
    fun SimpleTypeMarker.makeSimpleTypeDefinitelyNotNullOrNotNull(): SimpleTypeMarker
    fun SimpleTypeMarker.isMarkedNullable(): Boolean
    fun KotlinTypeMarker.isMarkedNullable(): Boolean =
        this is SimpleTypeMarker && isMarkedNullable()

    fun SimpleTypeMarker.withNullability(nullable: Boolean): SimpleTypeMarker
    fun SimpleTypeMarker.typeConstructor(): TypeConstructorMarker
    fun KotlinTypeMarker.withNullability(nullable: Boolean): KotlinTypeMarker

    fun CapturedTypeMarker.typeConstructor(): CapturedTypeConstructorMarker
    fun CapturedTypeMarker.captureStatus(): CaptureStatus
    fun CapturedTypeMarker.isProjectionNotNull(): Boolean
    fun CapturedTypeConstructorMarker.projection(): TypeArgumentMarker

    fun KotlinTypeMarker.argumentsCount(): Int
    fun KotlinTypeMarker.getArgument(index: Int): TypeArgumentMarker

    fun SimpleTypeMarker.getArgumentOrNull(index: Int): TypeArgumentMarker? {
        if (index in 0 until argumentsCount()) return getArgument(index)
        return null
    }

    fun SimpleTypeMarker.isStubType(): Boolean

    fun KotlinTypeMarker.asTypeArgument(): TypeArgumentMarker

    fun CapturedTypeMarker.lowerType(): KotlinTypeMarker?

    fun TypeArgumentMarker.isStarProjection(): Boolean
    fun TypeArgumentMarker.getVariance(): TypeVariance
    fun TypeArgumentMarker.getType(): KotlinTypeMarker

    fun TypeConstructorMarker.parametersCount(): Int
    fun TypeConstructorMarker.getParameter(index: Int): TypeParameterMarker
    fun TypeConstructorMarker.supertypes(): Collection<KotlinTypeMarker>
    fun TypeConstructorMarker.isIntersection(): Boolean
    fun TypeConstructorMarker.isClassTypeConstructor(): Boolean
    fun TypeConstructorMarker.isInterface(): Boolean
    fun TypeConstructorMarker.isIntegerLiteralTypeConstructor(): Boolean
    fun TypeConstructorMarker.isLocalType(): Boolean

    val TypeVariableTypeConstructorMarker.typeParameter: TypeParameterMarker?

    fun TypeParameterMarker.getVariance(): TypeVariance
    fun TypeParameterMarker.upperBoundCount(): Int
    fun TypeParameterMarker.getUpperBound(index: Int): KotlinTypeMarker
    fun TypeParameterMarker.getTypeConstructor(): TypeConstructorMarker
    fun TypeParameterMarker.hasRecursiveBounds(selfConstructor: TypeConstructorMarker): Boolean

    fun areEqualTypeConstructors(c1: TypeConstructorMarker, c2: TypeConstructorMarker): Boolean

    fun TypeConstructorMarker.isDenotable(): Boolean

    fun KotlinTypeMarker.lowerBoundIfFlexible(): SimpleTypeMarker = this.asFlexibleType()?.lowerBound() ?: this.asSimpleType()!!
    fun KotlinTypeMarker.upperBoundIfFlexible(): SimpleTypeMarker = this.asFlexibleType()?.upperBound() ?: this.asSimpleType()!!

    fun KotlinTypeMarker.isFlexible(): Boolean = asFlexibleType() != null

    fun KotlinTypeMarker.isDynamic(): Boolean = asFlexibleType()?.asDynamicType() != null
    fun KotlinTypeMarker.isCapturedDynamic(): Boolean =
        asSimpleType()?.asCapturedType()?.typeConstructor()?.projection()?.takeUnless { it.isStarProjection() }
            ?.getType()?.isDynamic() == true

    fun KotlinTypeMarker.isDefinitelyNotNullType(): Boolean = asSimpleType()?.asDefinitelyNotNullType() != null

    fun KotlinTypeMarker.hasFlexibleNullability() =
        lowerBoundIfFlexible().isMarkedNullable() != upperBoundIfFlexible().isMarkedNullable()

    fun KotlinTypeMarker.typeConstructor(): TypeConstructorMarker =
        (asSimpleType() ?: lowerBoundIfFlexible()).typeConstructor()

    fun KotlinTypeMarker.isNullableType(): Boolean

    fun KotlinTypeMarker.isNullableAny() = this.typeConstructor().isAnyConstructor() && this.isNullableType()
    fun KotlinTypeMarker.isNothing() = this.typeConstructor().isNothingConstructor() && !this.isNullableType()
    fun KotlinTypeMarker.isFlexibleNothing() =
        this is FlexibleTypeMarker && lowerBound().isNothing() && upperBound().isNullableNothing()

    fun KotlinTypeMarker.isNullableNothing() = this.typeConstructor().isNothingConstructor() && this.isNullableType()

    fun SimpleTypeMarker.isClassType(): Boolean = typeConstructor().isClassTypeConstructor()

    fun SimpleTypeMarker.fastCorrespondingSupertypes(constructor: TypeConstructorMarker): List<SimpleTypeMarker>? = null

    fun SimpleTypeMarker.isIntegerLiteralType(): Boolean = typeConstructor().isIntegerLiteralTypeConstructor()

    fun SimpleTypeMarker.possibleIntegerTypes(): Collection<KotlinTypeMarker>

    fun TypeConstructorMarker.isCommonFinalClassConstructor(): Boolean

    fun captureFromArguments(
        type: SimpleTypeMarker,
        status: CaptureStatus
    ): SimpleTypeMarker?

    fun captureFromExpression(type: KotlinTypeMarker): KotlinTypeMarker?

    fun SimpleTypeMarker.asArgumentList(): TypeArgumentListMarker

    operator fun TypeArgumentListMarker.get(index: Int): TypeArgumentMarker {
        return when (this) {
            is SimpleTypeMarker -> getArgument(index)
            is ArgumentList -> get(index)
            else -> error("unknown type argument list type: $this, ${this::class}")
        }
    }

    fun TypeArgumentListMarker.size(): Int {
        return when (this) {
            is SimpleTypeMarker -> argumentsCount()
            is ArgumentList -> size
            else -> error("unknown type argument list type: $this, ${this::class}")
        }
    }

    operator fun TypeArgumentListMarker.iterator() = object : Iterator<TypeArgumentMarker> {
        private var argumentIndex: Int = 0

        override fun hasNext(): Boolean = argumentIndex < size()

        override fun next(): TypeArgumentMarker {
            val argument = get(argumentIndex)
            argumentIndex += 1
            return argument
        }
    }

    fun TypeConstructorMarker.isAnyConstructor(): Boolean
    fun TypeConstructorMarker.isNothingConstructor(): Boolean

    /**
     *
     * SingleClassifierType is one of the following types:
     *  - classType
     *  - type for type parameter
     *  - captured type
     *
     * Such types can contains error types in our arguments, but type constructor isn't errorTypeConstructor
     */
    fun SimpleTypeMarker.isSingleClassifierType(): Boolean

    fun intersectTypes(types: List<KotlinTypeMarker>): KotlinTypeMarker
    fun intersectTypes(types: List<SimpleTypeMarker>): SimpleTypeMarker

    fun KotlinTypeMarker.isSimpleType(): Boolean = asSimpleType() != null

    fun SimpleTypeMarker.isPrimitiveType(): Boolean

    fun KotlinTypeMarker.getAnnotations(): List<AnnotationMarker>
}

enum class CaptureStatus {
    FOR_SUBTYPING,
    FOR_INCORPORATION,
    FROM_EXPRESSION
}

inline fun TypeArgumentListMarker.all(
    context: TypeSystemContext,
    crossinline predicate: (TypeArgumentMarker) -> Boolean
): Boolean = with(context) {
    repeat(size()) { index ->
        if (!predicate(get(index))) return false
    }
    return true
}

@OptIn(ExperimentalContracts::class)
fun requireOrDescribe(condition: Boolean, value: Any?) {
    contract {
        returns() implies condition
    }
    require(condition) {
        val typeInfo = if (value != null) {
            ", type = '${value::class}'"
        } else ""
        "Unexpected: value = '$value'$typeInfo"
    }
}
