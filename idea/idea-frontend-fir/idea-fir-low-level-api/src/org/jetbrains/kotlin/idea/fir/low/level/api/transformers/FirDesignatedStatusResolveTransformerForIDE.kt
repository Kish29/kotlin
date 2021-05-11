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
import org.jetbrains.kotlin.fir.resolve.transformers.FirDesignatedStatusResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.FirStatusResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.StatusComputationSession
import org.jetbrains.kotlin.fir.visitors.transformSingle
import org.jetbrains.kotlin.idea.fir.low.level.api.api.FirDeclarationUntypedDesignationWithFile
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensurePhase
import org.jetbrains.kotlin.idea.fir.low.level.api.util.ensureTargetPhase
import org.jetbrains.kotlin.idea.fir.low.level.api.util.targetContainingDeclaration

internal class FirDesignatedStatusResolveTransformerForIDE(
    private val designation: FirDeclarationUntypedDesignationWithFile,
    private val session: FirSession,
    private val scopeSession: ScopeSession,
) : FirLazyTransformerForIDE {

    private inner class FirDesignatedStatusResolveTransformerForIDE :
        FirStatusResolveTransformer(session, scopeSession, StatusComputationSession.Regular()) {

        private val designationChecker = DesignationChecker(designation)
        val targetApplied get() = designationChecker.targetIsVisited

        val designationTransformer = IDEDeclarationTransformer(designation)

//        override fun <F : FirClass<F>> transformClass(klass: FirClass<F>, data: FirResolvedDeclarationStatus?): FirStatement {
//            return storeClass(klass) {
//                klass.typeParameters.forEach { it.transformSingle(this, data) }
//                klass.replaceResolvePhase(transformerPhase)
//
//                designationTransformer.transformDeclarationContent(this, klass, data) {
//                    klass.declarations.forEach {
//                        it.replaceResolvePhase(transformerPhase)
//                        it.transformSingle(this, data)
//                    }
//                }
//                klass
//            } as FirStatement
//        }

        override fun transformRegularClass(regularClass: FirRegularClass, data: FirResolvedDeclarationStatus?): FirStatement =
            designationChecker.whenInDesignation(regularClass) { super.transformRegularClass(regularClass, data) }

        override fun transformAnonymousFunction(
            anonymousFunction: FirAnonymousFunction,
            data: FirResolvedDeclarationStatus?
        ): FirStatement =
            designationChecker.whenInDesignation(anonymousFunction) { super.transformAnonymousFunction(anonymousFunction, data) }

        override fun transformProperty(property: FirProperty, data: FirResolvedDeclarationStatus?): FirDeclaration =
            designationChecker.whenInDesignation(property) { super.transformProperty(property, data) }

        override fun transformSimpleFunction(simpleFunction: FirSimpleFunction, data: FirResolvedDeclarationStatus?): FirDeclaration =
            designationChecker.whenInDesignation(simpleFunction) { super.transformSimpleFunction(simpleFunction, data) }

        override fun transformTypeAlias(typeAlias: FirTypeAlias, data: FirResolvedDeclarationStatus?): FirDeclaration =
            designationChecker.whenInDesignation(typeAlias) { super.transformTypeAlias(typeAlias, data) }

        override fun transformConstructor(constructor: FirConstructor, data: FirResolvedDeclarationStatus?): FirDeclaration =
            designationChecker.whenInDesignation(constructor) { super.transformConstructor(constructor, data) }
    }

    private fun resolveTopLevelDeclaration(declaration: FirDeclaration) {
        val transformer = FirStatusResolveTransformer(
            session = session,
            scopeSession = scopeSession,
            statusComputationSession = StatusComputationSession.Regular()
        )
        declaration.transformSingle(transformer, null)
    }

    private fun resolveClassMember(containingClass: FirClass<*>, targetDeclaration: FirDeclaration) {

        val transformer = object : FirDesignatedStatusResolveTransformer(
            session = session,
            scopeSession = scopeSession,
            designation = designation.toSequence(includeTarget = true).iterator(),
            targetClass = if (targetDeclaration is FirRegularClass) targetDeclaration else containingClass,
            statusComputationSession = StatusComputationSession.Regular(),
            designationMapForLocalClasses = emptyMap(),
            scopeForLocalClass = null
        ) {

            override fun <F : FirClass<F>> transformClass(klass: FirClass<F>, data: FirResolvedDeclarationStatus?): FirStatement {
                if (klass != containingClass) return super.transformClass(klass, data)
                val result = storeClass(klass) {
                    targetDeclaration.transformSingle(this, data)
                }
                return result as FirStatement
            }
        }

        val firstItemInDesignation = designation.path.firstOrNull() ?: designation.declaration
        firstItemInDesignation.transformSingle(transformer, null)
    }

    override fun transformDeclaration() {
        if (designation.declaration.resolvePhase >= FirResolvePhase.STATUS) return
        designation.ensurePhase(FirResolvePhase.TYPES)

        val t = FirDesignatedStatusResolveTransformerForIDE()
        designation.firFile.transform<FirElement, FirResolvedDeclarationStatus?>(t, null)
        check(t.targetApplied)
//        val containingDeclaration = designation.targetContainingDeclaration()
//        if (containingDeclaration != null) {
//            check(containingDeclaration is FirClass<*>) { "Invalid designation - the parent is not a class" }
//            resolveClassMember(containingDeclaration, designation.declaration)
//        } else {
//            resolveTopLevelDeclaration(designation.declaration)
//        }

        designation.ensureTargetPhase(FirResolvePhase.STATUS)
    }
}