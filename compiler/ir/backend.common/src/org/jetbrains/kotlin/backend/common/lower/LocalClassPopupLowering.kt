/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.common.lower

import org.jetbrains.kotlin.backend.common.*
import org.jetbrains.kotlin.backend.common.ir.setDeclarationsParent
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrStatementContainer
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

//This lower takes part of old LocalDeclarationLowering job to pop up local classes from functions
open class LocalClassPopupLowering(val context: BackendContext) : BodyLoweringPass {
    override fun lower(irFile: IrFile) {
        runOnFilePostfix(irFile, withLocalDeclarations = true, allowDeclarationModification = true)
    }

    private data class ExtractedLocalClass(
        val local: IrClass, val newParent: IrDeclarationParent, val target: MutableList<in IrClass>, val insertBefore: IrDeclaration
    )

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        val extractedLocalClasses = arrayListOf<ExtractedLocalClass>()

        irBody.transform(object : IrElementTransformerVoidWithContext() {

            override fun visitClassNew(declaration: IrClass): IrStatement {
                val currentScope =
                    if (allScopes.size > 1) allScopes[allScopes.lastIndex - 1] else createScope(container as IrSymbolOwner)
                if (!shouldPopUp(declaration, currentScope)) return declaration

                var insertBefore: IrDeclaration = declaration
                var newContainer = declaration.parent
                while (newContainer is IrDeclaration && newContainer !is IrClass && newContainer !is IrScript) {
                    insertBefore = newContainer
                    newContainer = newContainer.parent
                }
                when (newContainer) {
                    is IrStatementContainer -> {
                        extractedLocalClasses.add(
                            ExtractedLocalClass(declaration, newContainer, newContainer.statements, insertBefore)
                        )
                    }
                    is IrDeclarationContainer -> {
                        extractedLocalClasses.add(
                            ExtractedLocalClass(declaration, newContainer, newContainer.declarations, insertBefore)
                        )
                    }
                    else -> error("Inexpected container type $newContainer")
                }

                return IrCompositeImpl(declaration.startOffset, declaration.endOffset, context.irBuiltIns.unitType)
            }
        }, null)

        // TODO: consider grouping by newContainer
        for (extracted in extractedLocalClasses) {
            val insertIndex = extracted.target.indexOf(extracted.insertBefore)
            if (insertIndex >= 0) {
                extracted.target.add(insertIndex, extracted.local)
            } else {
                extracted.target.add(extracted.local)
            }
            extracted.local.setDeclarationsParent(extracted.newParent)

            extracted.local.acceptVoid(object : IrElementVisitorVoid {
                override fun visitElement(element: IrElement) {
                    element.acceptChildrenVoid(this)
                }

                override fun visitBody(body: IrBody) {
                }

                override fun visitClass(declaration: IrClass) {
                    super.visitClass(declaration)
                    context.extractedLocalClasses += declaration
                }
            })
        }
    }

    protected open fun shouldPopUp(klass: IrClass, currentScope: ScopeWithIr?): Boolean =
        klass.isLocalNotInner()
}
