/*******************************************************************************
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *******************************************************************************/
package org.jetbrains.kotlin.ui.refactorings.rename

import org.eclipse.jdt.ui.actions.SelectionDispatchAction
import org.jetbrains.kotlin.ui.editors.KotlinFileEditor
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jdt.ui.actions.IJavaEditorActionDefinitionIds
import org.jetbrains.kotlin.eclipse.ui.utils.EditorUtil
import org.jetbrains.kotlin.core.references.getReferenceExpression
import org.jetbrains.kotlin.core.references.createReference
import org.jetbrains.kotlin.core.references.resolveToSourceElements
import org.jetbrains.kotlin.core.model.sourceElementsToLightElements
import org.eclipse.jdt.internal.ui.refactoring.reorg.RenameLinkedMode
import org.eclipse.jdt.core.IJavaElement
import org.eclipse.jface.text.link.LinkedModeModel
import org.eclipse.jface.text.link.LinkedPositionGroup
import org.eclipse.jface.text.link.LinkedPosition
import com.intellij.psi.PsiElement
import org.eclipse.jdt.internal.ui.javaeditor.EditorHighlightingSynchronizer
import org.eclipse.jface.text.link.ILinkedModeListener
import org.eclipse.ui.texteditor.link.EditorLinkedModeUI
import org.eclipse.jdt.internal.ui.text.correction.proposals.LinkedNamesAssistProposal.DeleteBlockingExitPolicy
import org.jetbrains.kotlin.eclipse.ui.utils.getTextDocumentOffset
import org.eclipse.jdt.core.refactoring.IJavaRefactorings
import org.eclipse.ltk.core.refactoring.RefactoringCore
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor
import org.eclipse.jdt.ui.refactoring.RenameSupport
import org.eclipse.jdt.core.IType
import org.eclipse.core.resources.IResource
import org.eclipse.core.resources.IFile
import org.eclipse.jdt.core.ICompilationUnit
import org.eclipse.jdt.internal.ui.javaeditor.EditorUtility
import org.eclipse.jdt.core.ISourceRange
import org.eclipse.jdt.core.IMethod
import org.eclipse.jdt.internal.core.CompilationUnit
import org.eclipse.text.edits.TextEdit
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.text.edits.UndoEdit
import org.eclipse.jdt.core.compiler.CharOperation
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit as ContentProviderCompilationUnit
import org.jetbrains.kotlin.psi.JetElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.JetDeclaration
import org.jetbrains.kotlin.core.model.toLightElements
import org.eclipse.jdt.core.IJavaProject
import org.jetbrains.kotlin.core.log.KotlinLogger

public class KotlinRenameAction(val editor: KotlinFileEditor) : SelectionDispatchAction(editor.getSite()) {
    init {
        setActionDefinitionId(IJavaEditorActionDefinitionIds.RENAME_ELEMENT)
    }
    
    companion object {
        val ACTION_ID = "RenameElement"
    }
    
    override fun run(selection: ITextSelection) {
        val jetElement = getJetElement(selection)
        if (jetElement == null) return
        
        val javaElements = resolveToJavaElements(jetElement, editor.javaProject!!)
        if (javaElements.isEmpty()) return
        
        if (javaElements.size() > 1) {
            KotlinLogger.logWarning("There are more than one (${javaElements.size()}) java elements for $jetElement")
        }
        
        beginRenameRefactoring(javaElements[0], jetElement, editor)
    }
    
    private fun getJetElement(selection: ITextSelection): JetElement? {
        val psiElement = EditorUtil.getPsiElement(editor, selection.getOffset())
        if (psiElement != null) {
            return PsiTreeUtil.getNonStrictParentOfType(psiElement, JetElement::class.java)
        }
        
        return null
    }
}

fun resolveToJavaElements(jetElement: JetElement, javaProject: IJavaProject): List<IJavaElement> {
    return when (jetElement) {
        is JetDeclaration -> jetElement.toLightElements(javaProject)
        else -> {
            val referenceExpression = getReferenceExpression(jetElement)
            if (referenceExpression == null) return emptyList()
            
            val sourceElements = createReference(referenceExpression).resolveToSourceElements()
            sourceElementsToLightElements(sourceElements, javaProject)
        }
    }
}

fun beginRenameRefactoring(javaElement: IJavaElement, jetElement: JetElement, editor: KotlinFileEditor) {
    val linkedPositionGroup = LinkedPositionGroup()
    val offsetInDocument = jetElement.getTextDocumentOffset(editor.document)
    
    val position = LinkedPosition(editor.document, offsetInDocument, jetElement.getTextLength())
    linkedPositionGroup.addPosition(position)
    
    val linkedModeModel = LinkedModeModel()
    linkedModeModel.addGroup(linkedPositionGroup)
    linkedModeModel.forceInstall()
    linkedModeModel.addLinkingListener(EditorHighlightingSynchronizer(editor))
    linkedModeModel.addLinkingListener(object : ILinkedModeListener {
        override fun left(model: LinkedModeModel, flags: Int) {
            if ((flags and ILinkedModeListener.UPDATE_CARET) != 0) {
                doRename(javaElement, jetElement, position.getContent(), editor)
            }
        }
        
        override fun resume(model: LinkedModeModel?, flags: Int) {
        }
        
        override fun suspend(model: LinkedModeModel?) {
        }
    })
    
    val ui = EditorLinkedModeUI(linkedModeModel, editor.getViewer())
    ui.setExitPosition(editor.getViewer(), offsetInDocument, 0, Integer.MAX_VALUE)
    ui.setExitPolicy(DeleteBlockingExitPolicy(editor.document))
    ui.enter()
}

fun doRename(javaElement: IJavaElement, jetElement: JetElement, newName: String, editor: KotlinFileEditor) {
//    val contributionId = IJavaRefactorings.RENAME_TYPE
//    val descriptor = RefactoringCore.getRefactoringContribution(contributionId).createDescriptor() as RenameJavaElementDescriptor
//    descriptor.setJavaElement(javaElement)
//    descriptor.setNewName(newName)
//    descriptor.setUpdateReferences(true)
    
    val kotlinLightType = KotlinLightType(javaElement as IType, jetElement, editor)
    val renameSupport = RenameSupport.create(kotlinLightType, newName, RenameSupport.UPDATE_REFERENCES)
    with(editor.getSite()) {
        renameSupport.perform(getShell(), getWorkbenchWindow())
    }
}