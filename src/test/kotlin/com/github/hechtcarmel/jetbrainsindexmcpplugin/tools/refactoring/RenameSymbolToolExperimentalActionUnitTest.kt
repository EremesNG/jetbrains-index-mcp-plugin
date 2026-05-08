package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import junit.framework.TestCase

class RenameSymbolToolExperimentalActionUnitTest : TestCase() {

    fun testPreferredHighLevelLaneAllowsProvenRiderBackendRenameHandler() {
        val assessment = RenameSymbolTool.assessPreferredRiderRenameHandlerClassName(
            "com.jetbrains.rdclient.actions.impl.BackendRenameHandler"
        )

        assertTrue(assessment.shouldInvoke)
        assertEquals("selected rename handler is on the proven Rider non-modal allowlist", assessment.reason)
    }

    fun testPreferredHighLevelLaneRejectsModalPsiElementRenameHandler() {
        val assessment = RenameSymbolTool.assessPreferredRiderRenameHandlerClassName(
            "com.intellij.refactoring.rename.PsiElementRenameHandler"
        )

        assertFalse(assessment.shouldInvoke)
        assertEquals("selected rename handler would show modal UI outside unit test mode", assessment.reason)
    }

    fun testPreferredHighLevelLaneAcceptsSingleSafeHandler() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol"),
            selectedHandlerClassName = "com.jetbrains.rdclient.actions.impl.BackendRenameHandler",
            selectedHandlerIsKnownSafeNonModal = true,
            selectedHandlerBlockReason = null
        )

        assertTrue(plan.shouldInvoke)
        assertEquals("single deterministic non-modal rename handler is available", plan.reason)
    }

    fun testPreferredHighLevelLaneRejectsMultipleHandlersToAvoidChooserUi() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol", "Rename file"),
            selectedHandlerClassName = null,
            selectedHandlerIsKnownSafeNonModal = false,
            selectedHandlerBlockReason = null
        )

        assertFalse(plan.shouldInvoke)
        assertEquals("multiple rename handlers would require chooser UI", plan.reason)
    }

    fun testPreferredHighLevelLaneRejectsNonDeterministicHandlerSelection() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol"),
            selectedHandlerClassName = null,
            selectedHandlerIsKnownSafeNonModal = false,
            selectedHandlerBlockReason = null
        )

        assertFalse(plan.shouldInvoke)
        assertEquals("rename handler selection did not resolve a concrete handler", plan.reason)
    }

    fun testPreferredHighLevelLaneRejectsModalUnsafeHandler() {
        val plan = RenameSymbolTool.evaluatePreferredRiderActionLane(
            availableHandlerTitles = listOf("Rename symbol"),
            selectedHandlerClassName = "com.intellij.refactoring.rename.PsiElementRenameHandler",
            selectedHandlerIsKnownSafeNonModal = false,
            selectedHandlerBlockReason = "selected rename handler would show modal UI outside unit test mode"
        )

        assertFalse(plan.shouldInvoke)
        assertEquals("selected rename handler would show modal UI outside unit test mode", plan.reason)
    }

    fun testBlockedRiderFrontendFallbackMapsActiveEditorRequirementToNeedsActiveEditor() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            backendStatus = "unsupported",
            actionReason = "active editor is required for Rider rename lane"
        )

        assertFalse(result.success)
        assertEquals("needs_active_editor", result.status)
        assertTrue(result.message.contains("active editor is required"))
    }

    fun testBlockedRiderFrontendFallbackMapsModalAndPreviewReasonsToConflict() {
        listOf(
            "production handler invoke would show modal UI",
            "rename preview would require user interaction"
        ).forEach { reason ->
            val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
                oldName = "WidgetService",
                backendStatus = "unsupported",
                actionReason = reason
            )

            assertFalse("reason=$reason should be non-success", result.success)
            assertEquals("reason=$reason should map to conflict", "conflict", result.status)
        }
    }

    fun testClassifyFrontendRenameFailureMapsNotSupportedMessageToUnsupportedStatus() {
        val classification = RenameSymbolTool.classifyFrontendRenameFailure(
            message = "Rename: Operation is not supported",
            exceptionClassName = "java.lang.IllegalStateException",
            riderFallbackStatus = "unsupported"
        )

        assertEquals("unsupported_context", classification.status)
        assertTrue(classification.userMessage.contains("Operation is not supported"))
    }

    fun testClassifyFrontendRenameFailureKeepsGenericErrorsAsErrors() {
        val classification = RenameSymbolTool.classifyFrontendRenameFailure(
            message = "Index is stale",
            exceptionClassName = "java.lang.IllegalStateException",
            riderFallbackStatus = "unsupported"
        )

        assertNull(classification.status)
        assertEquals("Rename failed: Index is stale", classification.userMessage)
    }

    fun testBlockedRiderFrontendFallbackMapsOtherFailClosedReasonsToUnsupportedContext() {
        val result = RenameSymbolTool.buildBlockedRiderFrontendFallbackResult(
            oldName = "WidgetService",
            backendStatus = "unsupported",
            actionReason = "experimental action fallback disabled"
        )

        assertFalse(result.success)
        assertEquals("unsupported_context", result.status)
        assertTrue(result.message.contains("experimental action fallback disabled"))
        assertTrue(result.message.contains("Backend reported 'unsupported'"))
    }

    fun testActiveEditorFeasibilityFailsClosedWhenDeclarationEditorIsMissing() {
        val feasibility = RenameSymbolTool.evaluateRiderFrontendFeasibility(
            hasDeclarationEditor = false,
            canComposeDataContext = false
        )

        assertFalse(feasibility.canProceed)
        assertEquals("active editor is required for Rider rename lane", feasibility.reason)
    }

    fun testActiveEditorFeasibilityAllowsPlanningWhenEditorAndContextExist() {
        val feasibility = RenameSymbolTool.evaluateRiderFrontendFeasibility(
            hasDeclarationEditor = true,
            canComposeDataContext = true
        )

        assertTrue(feasibility.canProceed)
        assertEquals("declaration editor and deterministic data context are available", feasibility.reason)
    }

    fun testSecondChoiceRefactoringFactoryLaneIsExplicitlyRefusedWithoutSafetyProof() {
        val plan = RenameSymbolTool.evaluateSecondChoiceRiderRefactoringFactoryLane(
            hasDeclarationEditor = true,
            canComposeDataContext = true,
            preferredLaneWasDeterministic = false
        )

        assertFalse(plan.shouldInvoke)
        assertTrue(plan.reason.contains("second choice"))
        assertTrue(plan.reason.contains("RefactoringFactory.createRename"))
        assertTrue(plan.reason.contains("non-modal"))
    }

    fun testSourceGatesRefactoringFactoryFallbackAsSecondChoiceLane() {
        val source = renameToolSource()

        assertTrue(source.contains("RefactoringFactory.createRename("))
        assertTrue(source.contains("second choice"))
        assertTrue(source.contains("frontend.factory.policy"))
        assertTrue(source.contains("frontend.factory.refused"))
    }

    fun testSourceAutoOpensRiderDeclarationEditorBeforeInvokingBackendHandler() {
        val source = renameToolSource()

        assertTrue(source.contains("openFile(virtualFile, true)"))
        assertTrue(source.contains("caretModel.moveToOffset"))
        assertTrue(source.contains("closeFile(virtualFile)"))
        assertTrue(source.contains("auto-opened declaration file for Rider rename lane"))
    }

    fun testVerifyRiderFrontendMutationFailsWhenNameAndTextStayUnchanged() {
        val check = RenameSymbolTool.verifyRiderFrontendMutation(
            beforeName = "OldName",
            afterName = "OldName",
            beforeFileText = "class OldName {}",
            afterFileText = "class OldName {}"
        )

        assertFalse(check.verified)
        assertTrue(check.reason.contains("remained unchanged"))
    }

    fun testVerifyRiderFrontendMutationSucceedsWhenTargetNameChanges() {
        val check = RenameSymbolTool.verifyRiderFrontendMutation(
            beforeName = "OldName",
            afterName = "NewName",
            beforeFileText = "class OldName {}",
            afterFileText = "class OldName {}"
        )

        assertTrue(check.verified)
        assertTrue(check.reason.contains("target name changed"))
    }

    fun testDialogAutomationWaitsForSecondDialogOnlyAfterNext() {
        assertTrue(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("Next"))
        assertTrue(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit(" next "))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("Refactor"))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("Rename"))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit("OK"))
        assertFalse(RenameSymbolTool.shouldAwaitSecondDialogAfterPrimarySubmit(null))
    }

    fun testDialogAutomationRecognizesSecondDialogTitles() {
        assertTrue(RenameSymbolTool.isSecondDialogCandidateTitle("Refactoring Preview"))
        assertTrue(RenameSymbolTool.isSecondDialogCandidateTitle("Rename Conflicts"))
        assertTrue(RenameSymbolTool.isSecondDialogCandidateTitle("Rename"))
        assertFalse(RenameSymbolTool.isSecondDialogCandidateTitle("Settings"))
        assertFalse(RenameSymbolTool.isSecondDialogCandidateTitle(""))
    }

    fun testSourceKeepsListeningForSecondDialogAfterNext() {
        val source = renameToolSource()

        assertTrue(source.contains("RIDER_DIALOG_SECOND_STAGE_TIMEOUT_MS"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.opened"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.components"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.button.clicked"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.timeout"))
        assertTrue(source.contains("frontend.dialog-automation.second-dialog.primary.closed"))
    }

    fun testSourcePollsForDialogReadinessBeforePrimaryAutomation() {
        val source = renameToolSource()

        assertTrue(source.contains("frontend.dialog-automation.readiness.waiting"))
        assertTrue(source.contains("frontend.dialog-automation.readiness.ready"))
        assertTrue(source.contains("frontend.dialog-automation.readiness.timeout"))
        assertTrue(source.contains("Timer(200)"))
    }

    private fun renameToolSource(): String {
        return java.io.File(
            "src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/refactoring/RenameSymbolTool.kt"
        ).readText()
    }
}
