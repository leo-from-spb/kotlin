/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend

import org.jetbrains.kotlin.backend.common.descriptors.WrappedPropertyDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyGetter
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertySetter
import org.jetbrains.kotlin.fir.descriptors.FirModuleDescriptor
import org.jetbrains.kotlin.fir.expressions.FirConstExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFileImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrPropertyImpl
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrErrorCallExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments
import org.jetbrains.kotlin.psi2ir.PsiSourceManager

class Fir2IrVisitor(
    private val session: FirSession,
    private val moduleDescriptor: FirModuleDescriptor,
    private val symbolTable: SymbolTable
) : FirVisitor<IrElement, Nothing?>() {
    private fun ModuleDescriptor.findPackageFragmentForFile(file: FirFile): PackageFragmentDescriptor =
        getPackage(file.packageFqName).fragments.first()

    private val parentStack = mutableListOf<IrDeclarationParent>()

    private fun <T : IrDeclarationParent> T.withParent(f: T.() -> Unit): T {
        parentStack += this
        f()
        parentStack.removeAt(parentStack.size - 1)
        return this
    }

    override fun visitElement(element: FirElement, data: Nothing?): IrElement {
        throw AssertionError("Should not be here")
    }

    override fun visitFile(file: FirFile, data: Nothing?): IrFile {
        return IrFileImpl(
            PsiSourceManager.PsiFileEntry(file.psi as KtFile),
            moduleDescriptor.findPackageFragmentForFile(file)
        ).withParent {
            file.declarations.forEach {
                declarations += it.accept(this@Fir2IrVisitor, data) as IrDeclaration
            }

            file.annotations.forEach {
                annotations += it.accept(this@Fir2IrVisitor, data) as IrCall
            }
        }
    }

    override fun visitProperty(property: FirProperty, data: Nothing?): IrProperty {
        val ktProperty = property.psi as KtProperty
        val parent = parentStack.last()
        val descriptor = WrappedPropertyDescriptor()
        val startOffset = ktProperty.startOffsetSkippingComments
        val endOffset = ktProperty.endOffset
        val origin = IrDeclarationOrigin.DEFINED
        return symbolTable.declareProperty(
            startOffset, endOffset,
            origin, descriptor, property.delegate != null
        ) { symbol ->
            IrPropertyImpl(
                startOffset, endOffset, origin, symbol,
                property.name, property.visibility, property.modality!!,
                property.isVar, property.isConst, property.isLateInit,
                property.delegate != null,
                // TODO
                isExternal = false
            )
        }.apply {
            this.parent = parent
            descriptor.bind(this)
            val initializer = property.initializer
            // TODO: this check is very preliminary, FIR resolve should determine backing field presence itself
            if (initializer != null) {
                backingField = symbolTable.declareField(
                    startOffset, endOffset,
                    IrDeclarationOrigin.PROPERTY_BACKING_FIELD,
                    descriptor, property.returnTypeRef.toIrType(session)
                ).apply {
                    val initializerExpression = initializer.accept(this@Fir2IrVisitor, data) as IrExpression
                    this.initializer = IrExpressionBodyImpl(initializerExpression)
                }
            }
            if (property.getter !is FirDefaultPropertyGetter) {
                getter = property.getter.accept(this@Fir2IrVisitor, data) as IrSimpleFunction
            }
            if (property.setter !is FirDefaultPropertySetter) {
                setter = property.getter.accept(this@Fir2IrVisitor, data) as IrSimpleFunction
            }
            property.annotations.forEach {
                annotations += it.accept(this@Fir2IrVisitor, data) as IrCall
            }
        }
    }

    override fun visitFunctionCall(functionCall: FirFunctionCall, data: Nothing?): IrElement {
        val ktCallElement = functionCall.psi!!
        val type = functionCall.typeRef.toIrType(session)
        val functionSymbol = functionCall.calleeReference.toFunctionSymbol(symbolTable)
        return if (functionSymbol != null) {
            IrCallImpl(
                ktCallElement.startOffsetSkippingComments, ktCallElement.endOffset,
                type, functionSymbol
            ).apply {
                for ((index, argument) in functionCall.arguments.withIndex()) {
                    val argumentExpression = argument.accept(this@Fir2IrVisitor, data) as IrExpression
                    putValueArgument(index, argumentExpression)
                }
            }
        } else {
            IrErrorCallExpressionImpl(
                ktCallElement.startOffsetSkippingComments, ktCallElement.endOffset,
                type, "Unresolved reference: ${functionCall.calleeReference.name}"
            ).apply {
                for (argument in functionCall.arguments) {
                    addArgument(argument.accept(this@Fir2IrVisitor, data) as IrExpression)
                }
            }
        }
    }

    override fun <T> visitConstExpression(constExpression: FirConstExpression<T>, data: Nothing?): IrElement {
        val ktConstExpression = constExpression.psi!!
        return IrConstImpl(
            ktConstExpression.startOffsetSkippingComments, ktConstExpression.endOffset,
            constExpression.typeRef.toIrType(session),
            constExpression.kind, constExpression.value
        )
    }
}