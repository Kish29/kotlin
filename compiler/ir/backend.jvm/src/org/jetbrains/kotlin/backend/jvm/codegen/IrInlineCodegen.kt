/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.codegen

import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.backend.jvm.ir.isInlineClassType
import org.jetbrains.kotlin.backend.jvm.ir.isInlineParameter
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.InlineClassAbi
import org.jetbrains.kotlin.codegen.IrExpressionLambda
import org.jetbrains.kotlin.codegen.JvmKotlinType
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.codegen.ValueKind
import org.jetbrains.kotlin.codegen.inline.*
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.descriptors.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.resolve.jvm.AsmTypes
import org.jetbrains.kotlin.resolve.jvm.jvmSignature.JvmMethodSignature
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.commons.Method
import org.jetbrains.org.objectweb.asm.tree.MethodNode

class IrInlineCodegen(
    codegen: ExpressionCodegen,
    state: GenerationState,
    private val function: IrFunction,
    methodOwner: Type,
    signature: JvmMethodSignature,
    typeParameterMappings: TypeParameterMappings<IrType>,
    sourceCompiler: SourceCompilerForInline,
    reifiedTypeInliner: ReifiedTypeInliner<IrType>
) :
    InlineCodegen<ExpressionCodegen>(
        codegen, state, function.toIrBasedDescriptor(), methodOwner, signature, typeParameterMappings, sourceCompiler, reifiedTypeInliner
    ),
    IrInlineCallGenerator {

    override fun generateAssertFieldIfNeeded(info: RootInliningContext) {
        if (info.generateAssertField) {
            // May be inlining code into `<clinit>`, in which case it's too late to modify the IR and
            // `generateAssertFieldIfNeeded` will return a statement for which we need to emit bytecode.
            val isClInit = info.callSiteInfo.functionName == "<clinit>"
            codegen.classCodegen.generateAssertFieldIfNeeded(isClInit)?.accept(codegen, BlockInfo())?.discard()
        }
    }

    override fun putClosureParametersOnStack(next: LambdaInfo, functionReferenceReceiver: StackValue?) {
        activeLambda = next

        when (next) {
            is IrExpressionLambdaImpl -> next.reference.getArgumentsWithIr().forEachIndexed { index, (_, ir) ->
                putCapturedValueOnStack(ir, next.capturedParamsInDesc[index], index)
            }
            is IrDefaultLambda -> rememberCapturedForDefaultLambda(next)
            else -> throw RuntimeException("Unknown lambda: $next")
        }

        activeLambda = null
    }

    override fun genValueAndPut(
        irValueParameter: IrValueParameter,
        argumentExpression: IrExpression,
        parameterType: Type,
        codegen: ExpressionCodegen,
        blockInfo: BlockInfo
    ) {
        val isInlineParameter = irValueParameter.isInlineParameter()
        if (isInlineParameter && argumentExpression.isInlineIrExpression()) {
            val irReference = (argumentExpression as IrBlock).statements.filterIsInstance<IrFunctionReference>().single()
            val lambdaInfo = IrExpressionLambdaImpl(codegen, irReference, irValueParameter)
            val closureInfo = invocationParamBuilder.addNextValueParameter(parameterType, true, null, irValueParameter.index)
            closureInfo.functionalArgument = lambdaInfo
            expressionMap[closureInfo.index] = lambdaInfo
            val boundReceiver = irReference.extensionReceiver
            if (boundReceiver != null) {
                activeLambda = lambdaInfo
                putCapturedValueOnStack(boundReceiver, lambdaInfo.capturedParamsInDesc.single(), 0)
                activeLambda = null
            }
        } else {
            val kind = when (irValueParameter.origin) {
                IrDeclarationOrigin.MASK_FOR_DEFAULT_FUNCTION -> ValueKind.DEFAULT_MASK
                IrDeclarationOrigin.METHOD_HANDLER_IN_DEFAULT_FUNCTION -> ValueKind.METHOD_HANDLE_IN_DEFAULT
                else -> when {
                    argumentExpression is IrContainerExpression && argumentExpression.origin == IrStatementOrigin.DEFAULT_VALUE ->
                        ValueKind.DEFAULT_PARAMETER
                    // TODO ValueKind.NON_INLINEABLE_ARGUMENT_FOR_INLINE_PARAMETER_CALLED_IN_SUSPEND?
                    isInlineParameter && irValueParameter.type.isSuspendFunctionTypeOrSubtype() ->
                        ValueKind.NON_INLINEABLE_ARGUMENT_FOR_INLINE_SUSPEND_PARAMETER
                    else -> ValueKind.GENERAL
                }
            }

            val onStack = when {
                kind == ValueKind.METHOD_HANDLE_IN_DEFAULT -> StackValue.constant(null, AsmTypes.OBJECT_TYPE)
                kind == ValueKind.DEFAULT_MASK -> StackValue.constant((argumentExpression as IrConst<*>).value, Type.INT_TYPE)
                kind == ValueKind.DEFAULT_PARAMETER -> StackValue.constant(null, AsmTypes.OBJECT_TYPE)
                irValueParameter.index >= 0
                    // Reuse an existing local if possible. NOTE: when stopping at a breakpoint placed
                    // in an inline function, arguments which reuse an existing local will not be visible
                    // in the debugger.
                -> codegen.genOrGetLocal(argumentExpression, parameterType, irValueParameter.type, blockInfo)
                else
                    // Do not reuse locals for receivers. While it's actually completely fine, the non-IR
                    // backend does not do it for internal reasons, and here we replicate the debugging
                    // experience.
                -> codegen.gen(argumentExpression, parameterType, irValueParameter.type, blockInfo)
            }


            //TODO support default argument erasure
            if (!processDefaultMaskOrMethodHandler(onStack, kind)) {
                val expectedType = JvmKotlinType(parameterType, irValueParameter.type.toIrBasedKotlinType())
                putArgumentOrCapturedToLocalVal(expectedType, onStack, -1, irValueParameter.index, kind)
            }
        }
    }

    private fun putCapturedValueOnStack(argumentExpression: IrExpression, valueType: Type, capturedParamIndex: Int) {
        val onStack = codegen.genOrGetLocal(argumentExpression, valueType, argumentExpression.type, BlockInfo())
        val expectedType = JvmKotlinType(valueType, argumentExpression.type.toIrBasedKotlinType())
        putArgumentOrCapturedToLocalVal(expectedType, onStack, capturedParamIndex, capturedParamIndex, ValueKind.CAPTURED)
    }

    override fun beforeValueParametersStart() {
        invocationParamBuilder.markValueParametersStart()
    }

    override fun genInlineCall(
        callableMethod: IrCallableMethod,
        codegen: ExpressionCodegen,
        expression: IrFunctionAccessExpression,
        isInsideIfCondition: Boolean,
    ) {
        performInline(
            expression.symbol.owner.typeParameters.map { it.symbol },
            // Always look for default lambdas to allow custom default argument handling in compiler plugins.
            true,
            false,
            codegen.typeMapper.typeSystem,
            registerLineNumberAfterwards = isInsideIfCondition,
        )
    }

    override fun genCycleStub(text: String, codegen: ExpressionCodegen) {
        generateStub(text, codegen)
    }

    override fun extractDefaultLambdas(node: MethodNode): List<DefaultLambda> {
        if (maskStartIndex == -1) return listOf()
        return expandMaskConditionsAndUpdateVariableNodes(
            node, maskStartIndex, maskValues, methodHandleInDefaultMethodIndex,
            extractDefaultLambdaOffsetAndDescriptor(jvmSignature, function),
            ::IrDefaultLambda
        )
    }

    override fun descriptorIsDeserialized(memberDescriptor: CallableMemberDescriptor): Boolean =
        ((memberDescriptor as IrBasedDeclarationDescriptor<*>).owner as IrMemberWithContainerSource).parentClassId != null
}

class IrExpressionLambdaImpl(
    codegen: ExpressionCodegen,
    val reference: IrFunctionReference,
    irValueParameter: IrValueParameter
) : ExpressionLambda(irValueParameter.isCrossinline), IrExpressionLambda {
    override val isExtensionLambda: Boolean = irValueParameter.type.isExtensionFunctionType

    val function: IrFunction
        get() = reference.symbol.owner

    override val isBoundCallableReference: Boolean
        get() = reference.extensionReceiver != null

    override val isSuspend: Boolean = function.isSuspend

    // This name doesn't actually matter: it is used internally to tell this lambda's captured
    // arguments apart from any other scope's. So long as it's unique, any value is fine.
    // This particular string slightly aids in debugging internal compiler errors as it at least
    // points towards the function containing the lambda.
    override val lambdaClassType: Type = codegen.context.getLocalClassType(reference)
        ?: throw AssertionError("callable reference ${reference.dump()} has no name in context")

    private val capturedParameters: Map<CapturedParamDesc, IrValueParameter> =
        reference.getArgumentsWithIr().associate { (param, _) ->
            capturedParamDesc(param.name.asString(), codegen.typeMapper.mapType(param.type)) to param
        }

    override val capturedVars: List<CapturedParamDesc> = capturedParameters.keys.toList()

    private val loweredMethod = codegen.methodSignatureMapper.mapAsmMethod(function)

    private val captureParameterIndices: Pair<Int, Int>
        get() = when {
            isBoundCallableReference -> 0 to 1 // (bound receiver, real parameters...)
            isExtensionLambda -> 1 to capturedVars.size + 1 // (unbound receiver, captures..., real parameters...)
            else -> 0 to capturedVars.size // (captures..., real parameters...)
        }

    val capturedParamsInDesc: List<Type> =
        captureParameterIndices.let { (from, to) -> loweredMethod.argumentTypes.take(to).drop(from) }

    override val invokeMethod: Method = loweredMethod.let {
        val (startCapture, endCapture) = captureParameterIndices
        Method(it.name, it.returnType, (it.argumentTypes.take(startCapture) + it.argumentTypes.drop(endCapture)).toTypedArray())
    }

    override val invokeMethodParameters: List<KotlinType?>
        get() {
            val allParameters = function.explicitParameters.map { it.type.toIrBasedKotlinType() }
            val (startCapture, endCapture) = captureParameterIndices
            return allParameters.take(startCapture) + allParameters.drop(endCapture)
        }

    override val invokeMethodReturnType: KotlinType
        get() = function.returnType.toIrBasedKotlinType()

    override val hasDispatchReceiver: Boolean = false

    override fun isCapturedSuspend(desc: CapturedParamDesc): Boolean =
        capturedParameters[desc]?.let { it.isInlineParameter() && it.type.isSuspendFunctionTypeOrSubtype() } == true
}

class IrDefaultLambda(
    override val lambdaClassType: Type,
    capturedArgs: Array<Type>,
    private val irValueParameter: IrValueParameter,
    offset: Int,
    needReification: Boolean
) : DefaultLambda(capturedArgs, irValueParameter.isCrossinline, offset, needReification) {
    private lateinit var typeArguments: List<IrType>

    override val invokeMethodParameters: List<KotlinType>
        get() = typeArguments.dropLast(1).map { it.toIrBasedKotlinType() }

    override val invokeMethodReturnType: KotlinType
        get() = typeArguments.last().toIrBasedKotlinType()

    override fun mapAsmMethod(sourceCompiler: SourceCompilerForInline, isPropertyReference: Boolean): Method {
        val context = (sourceCompiler as IrSourceCompilerForInline).codegen.context
        typeArguments = (irValueParameter.type as IrSimpleType).arguments.let {
            if (isPropertyReference) {
                // Property references: `(A) -> B` => `get(Any?): Any?`
                List(it.size) { context.irBuiltIns.anyNType }
            } else {
                // Non-suspend function references and lambdas: `(A) -> B` => `invoke(A): B`
                // Suspend function references: `suspend (A) -> B` => `invoke(A, Continuation<B>): Any?`
                // TODO: default suspend lambdas are currently uninlinable
                it.mapTo(mutableListOf()) { argument -> (argument as IrTypeProjection).type }.apply {
                    if (irValueParameter.type.isSuspendFunction()) {
                        set(size - 1, context.ir.symbols.continuationClass.typeWith(get(size - 1)))
                        add(context.irBuiltIns.anyNType)
                    }
                }
            }
        }
        val base = if (isPropertyReference) OperatorNameConventions.GET.asString() else OperatorNameConventions.INVOKE.asString()
        val name = InlineClassAbi.hashSuffix(
            context.state.useOldManglingSchemeForFunctionsWithInlineClassesInSignatures,
            typeArguments.dropLast(1),
            typeArguments.last().takeIf { it.isInlineClassType() }
        )?.let { "$base-$it" } ?: base
        // TODO: while technically only the number of arguments here matters right now (see DefaultLambdaInfo.generateLambdaBody),
        //       it would be better to map to a non-erased signature if not a property reference.
        return Method(name, AsmTypes.OBJECT_TYPE, Array(typeArguments.size - 1) { AsmTypes.OBJECT_TYPE })
    }
}

fun IrExpression.isInlineIrExpression() =
    when (this) {
        is IrBlock -> origin.isInlineIrExpression()
        is IrCallableReference<*> -> true.also {
            assert((0 until valueArgumentsCount).count { getValueArgument(it) != null } == 0) {
                "Expecting 0 value arguments for bounded callable reference: ${dump()}"
            }
        }
        else -> false
    }

fun IrStatementOrigin?.isInlineIrExpression() =
    isLambda || this == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE || this == IrStatementOrigin.SUSPEND_CONVERSION

fun IrFunction.isInlineFunctionCall(context: JvmBackendContext) =
    (!context.state.isInlineDisabled || typeParameters.any { it.isReified }) && (isInline || isInlineArrayConstructor(context))

// Constructors can't be marked as inline in metadata, hence this hack.
private fun IrFunction.isInlineArrayConstructor(context: JvmBackendContext): Boolean =
    this is IrConstructor && valueParameters.size == 2 && constructedClass.symbol.let {
        it == context.irBuiltIns.arrayClass || it in context.irBuiltIns.primitiveArrays
    }
