/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.backend

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.descriptors.FirModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.impl.IrModuleFragmentImpl
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.TypeTranslator

object Fir2IrConverter {

    fun createModuleFragment(
        session: FirSession,
        firFiles: List<FirFile>,
        languageVersionSettings: LanguageVersionSettings
    ): IrModuleFragment {
        val moduleDescriptor = FirModuleDescriptor(session)
        val symbolTable = SymbolTable()
        val fir2irTransformer = Fir2IrVisitor(session, moduleDescriptor, symbolTable)
        val irFiles = mutableListOf<IrFile>()
        for (firFile in firFiles) {
            irFiles += firFile.accept(fir2irTransformer, null) as IrFile
        }
        val typeTranslator = TypeTranslator(symbolTable, languageVersionSettings)
        val builtIns = IrBuiltIns(moduleDescriptor.builtIns, typeTranslator)

        return IrModuleFragmentImpl(moduleDescriptor, builtIns, irFiles)
    }
}