/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.FirApplySupertypesTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.FirProviderInterceptor
import org.jetbrains.kotlin.fir.resolve.transformers.FirSupertypeResolverVisitor
import org.jetbrains.kotlin.fir.resolve.transformers.SupertypeComputationSession
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePathPhase
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensureTargetPhaseIfClass

internal class FirDesignatedSupertypeResolverTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    private val session: FirSession,
    private val scopeSession: ScopeSession,
    private val firProviderInterceptor: FirProviderInterceptor?
) : FirLazyTransformerForIDE {

    private val supertypeComputationSession = SupertypeComputationSession()

    private inner class DesignatedFirSupertypeResolverVisitor : FirSupertypeResolverVisitor(
        session = session,
        supertypeComputationSession = supertypeComputationSession,
        scopeSession = scopeSession,
        scopeForLocalClass = null,
        localClassesNavigationInfo = null,
        firProviderInterceptor = firProviderInterceptor,
    ) {
        private val designationChecker = DesignationChecker(designation)
        val targetApplied get() = designationChecker.targetIsVisited

        override fun visitAnonymousObject(anonymousObject: FirAnonymousObject, data: Any?) {
            designationChecker.whenInDesignation(anonymousObject) { super.visitAnonymousObject(anonymousObject, data) }
        }

        override fun visitProperty(property: FirProperty, data: Any?) {
            designationChecker.whenInDesignation(property) { super.visitProperty(property, data) }
        }

        override fun visitSimpleFunction(simpleFunction: FirSimpleFunction, data: Any?) {
            designationChecker.whenInDesignation(simpleFunction) { super.visitSimpleFunction(simpleFunction, data) }
        }

        override fun visitTypeAlias(typeAlias: FirTypeAlias, data: Any?) {
            designationChecker.whenInDesignation(typeAlias) { super.visitTypeAlias(typeAlias, data) }
        }

        override fun visitConstructor(constructor: FirConstructor, data: Any?) {
            designationChecker.whenInDesignation(constructor) { super.visitConstructor(constructor, data) }
        }

        override fun visitRegularClass(regularClass: FirRegularClass, data: Any?) {
            designationChecker.whenInDesignation(regularClass) { super.visitRegularClass(regularClass, data) }
        }
    }

    private inner class DesignatedFirApplySupertypesTransformer :
        FirApplySupertypesTransformer(supertypeComputationSession) {

        private val designationChecker = DesignationChecker(designation)
        val targetApplied get() = designationChecker.targetIsVisited

        override fun transformRegularClass(regularClass: FirRegularClass, data: Any?): FirStatement =
            designationChecker.whenInDesignation(regularClass) { super.transformRegularClass(regularClass, data) }

        override fun transformAnonymousFunction(anonymousFunction: FirAnonymousFunction, data: Any?): FirStatement =
            designationChecker.whenInDesignation(anonymousFunction) { super.transformAnonymousFunction(anonymousFunction, data) }

        override fun transformProperty(property: FirProperty, data: Any?): FirDeclaration =
            designationChecker.whenInDesignation(property) { super.transformProperty(property, data) }

        override fun transformSimpleFunction(simpleFunction: FirSimpleFunction, data: Any?): FirDeclaration =
            designationChecker.whenInDesignation(simpleFunction) { super.transformSimpleFunction(simpleFunction, data) }

        override fun transformTypeAlias(typeAlias: FirTypeAlias, data: Any?): FirDeclaration =
            designationChecker.whenInDesignation(typeAlias) { super.transformTypeAlias(typeAlias, data) }

        override fun transformConstructor(constructor: FirConstructor, data: Any?): FirDeclaration =
            designationChecker.whenInDesignation(constructor) { super.transformConstructor(constructor, data) }
    }

    override fun transformDeclaration() {
        if (designation.declaration.resolvePhase >= FirResolvePhase.SUPER_TYPES) return
        if (designation.declaration is FirTypeAlias) {
            designation.declaration.replaceResolvePhase(FirResolvePhase.SUPER_TYPES)
            return
        }
        check(designation.firFile.resolvePhase >= FirResolvePhase.IMPORTS) {
            "Invalid resolve phase of file. Should be IMPORTS but found ${designation.firFile.resolvePhase}"
        }

        val resolver = DesignatedFirSupertypeResolverVisitor()
        designation.firFile.accept(resolver, null)
        if (!resolver.targetApplied) {
            designation.declaration.accept(resolver, null)
        }

        val applier = DesignatedFirApplySupertypesTransformer()
        designation.firFile.transform<FirElement, Void?>(applier, null)
        if (!applier.targetApplied) {
            designation.declaration.transform<FirElement, Void?>(applier, null)
        }

        designation.ensurePathPhase(FirResolvePhase.SUPER_TYPES)
        designation.ensureTargetPhaseIfClass(FirResolvePhase.SUPER_TYPES)
    }
}
