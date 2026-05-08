package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import junit.framework.TestCase
import java.io.File

class RiderMutationRoutingUnitTest : TestCase() {

    fun testRenameSymbolToolKeepsRiderDotNetSymbolRenameOnBackendPath() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "RiderBackendSemanticService.isDotNetFile(file)",
            "Rider .cs/.csx symbol rename should stay on the Rider .NET routing check"
        )
        assertContains(
            source,
            "invokeCallResult(model, \"renameSymbol\", request)",
            "Rider .cs/.csx symbol rename should keep using the dedicated Rider backend rename endpoint"
        )
    }

    fun testRenameSymbolToolContractsSeparateRiderDotNetFileRenameEndpoint() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "renameFile",
            "Rider .cs/.csx file rename should use a distinct Rider backend endpoint instead of reusing symbol rename or generic file rename"
        )
        assertFalse(
            "Rider .cs/.csx routing should not stay gated to symbol rename only once file rename is supported",
            source.contains("if (!isFileRename && RiderBackendSemanticService.isDotNetFile(file))")
        )
    }

    fun testRenameSymbolToolPreservesBackendStatusAndVerificationFields() {
        val source = refactoringSource("RenameSymbolTool.kt")

        assertContains(
            source,
            "backendResult.status",
            "Rider rename mapping should preserve backend mutation status instead of fabricating frontend success"
        )
        assertContains(
            source,
            "backendResult.verification",
            "Rider rename mapping should preserve backend verification evidence instead of dropping it"
        )
    }

    fun testSharedMutationVerificationSummaryKeepsLimitedVerificationObservable() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = true,
            status = "success",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 1,
            message = "Rename applied with bounded verification.",
            verification = MutationVerification(
                status = "limited",
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("Closed-file diagnostics are supplementary only")
            ),
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("limited", summary.verification?.status)
        assertEquals(listOf("src/Service.cs"), summary.affectedFiles)
        assertEquals(1, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryDowngradesFailedVerificationToNonSuccess() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = true,
            status = "success",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 1,
            message = "Move applied but semantic verification failed.",
            verification = MutationVerification(
                status = "failed",
                checksRun = listOf("post_change_semantics"),
                warnings = listOf("Semantic verification failed")
            ),
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("failed", summary.status)
        assertEquals("failed", summary.verification?.status)
        assertEquals(listOf("src/Service.cs"), summary.affectedFiles)
        assertEquals(1, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryMapsBlockedToCanonicalUnsupportedContextByDefault() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = false,
            status = "blocked",
            affectedFiles = emptyList(),
            changesCount = 0,
            message = "Rename remained blocked because the workflow requires preview interaction.",
            verification = null,
            contract = RiderMutationResultMapper.StatusContract.CANONICAL
        )

        assertFalse(summary.success)
        assertEquals("conflict", summary.status)
        assertTrue(summary.affectedFiles.isEmpty())
        assertEquals(0, summary.changesCount)
    }

    fun testSharedMutationVerificationSummaryCanPreserveBlockedForSafeDeleteContracts() {
        val summary = RiderMutationResultMapper.summary(
            legacySuccess = false,
            status = "blocked",
            affectedFiles = listOf("src/Service.cs"),
            changesCount = 0,
            message = "Deletion blocked by usages.",
            verification = null,
            contract = RiderMutationResultMapper.StatusContract.PRESERVE_BLOCKED
        )

        assertFalse(summary.success)
        assertEquals("blocked", summary.status)
        assertTrue(summary.affectedFiles.isEmpty())
        assertEquals(0, summary.changesCount)
    }

    fun testMoveFileToolContractsRiderDotNetBackendMoveEndpoint() {
        val source = refactoringSource("MoveFileTool.kt")

        assertContains(
            source,
            "moveFile",
            "Rider .cs/.csx move should route through a Rider backend move endpoint"
        )
        assertFalse(
            "Rider .cs/.csx move should not keep the Rider frontend limitation warning once backend routing exists",
            source.contains("cross-file using/import updates depend on Rider frontend support")
        )
    }

    fun testSafeDeleteToolContractsRiderDotNetBackendDeleteEndpoint() {
        val source = refactoringSource("SafeDeleteTool.kt")

        assertContains(
            source,
            "safeDelete",
            "Rider .cs/.csx safe delete should route through a Rider backend endpoint"
        )
        assertContains(
            source,
            "RiderBackendSemanticService.isDotNetFile(file)",
            "Rider .cs/.csx safe delete should detect Rider-backed .NET files before generic deletion"
        )
    }

    fun testGenericFallbackRemainsAvailableForNonDotNetTargets() {
        val renameSource = refactoringSource("RenameSymbolTool.kt")
        val moveSource = refactoringSource("MoveFileTool.kt")
        val deleteSource = refactoringSource("SafeDeleteTool.kt")

        assertContains(
            renameSource,
            "validateAndPrepareFileRename(project, file, newName)",
            "Non-.NET file rename should keep the generic IDE rename fallback"
        )
        assertContains(
            moveSource,
            "MoveBackendSelection.GenericFileMove",
            "Non-.NET move should keep the generic IDE move fallback"
        )
        assertContains(
            deleteSource,
            "targetType",
            "Non-.NET safe delete should keep the generic tool routing entry point"
        )
    }

    fun testRiderMutationToolsUseSharedVerificationSummaryMapper() {
        val renameSource = refactoringSource("RenameSymbolTool.kt")
        val moveSource = refactoringSource("MoveFileTool.kt")
        val deleteSource = refactoringSource("SafeDeleteTool.kt")

        assertContains(renameSource, "RiderMutationResultMapper.summary", "Rename mapping should use the shared Rider mutation summary mapper")
        assertContains(moveSource, "RiderMutationResultMapper.summary", "Move mapping should use the shared Rider mutation summary mapper")
        assertContains(deleteSource, "RiderMutationResultMapper.summary", "Safe delete mapping should use the shared Rider mutation summary mapper")
    }

    private fun refactoringSource(fileName: String): String {
        return File("src/main/kotlin/com/github/hechtcarmel/jetbrainsindexmcpplugin/tools/refactoring/$fileName").readText()
    }

    private fun assertContains(source: String, needle: String, message: String) {
        assertTrue(message, source.contains(needle))
    }
}
