/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.expressions.impl

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.types.FirTypeRef

class FirQualifiedAccessExpressionImpl(
    session: FirSession,
    psi: PsiElement?,
    safe: Boolean = false
) : FirAbstractQualifiedAccess(session, psi, safe), FirQualifiedAccessExpression {
    override var typeRef: FirTypeRef? = null
}