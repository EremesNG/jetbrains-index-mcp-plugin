package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.MutationVerification
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reformats code in a file according to the project's code style settings.
 *
 * Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / Cmd+Opt+L).
 * Uses IntelliJ's [ReformatCodeProcessor] with optional chaining to
 * [OptimizeImportsProcessor] and [RearrangeCodeProcessor].
 *
 * Respects .editorconfig, project code style, and language-specific formatting rules.
 *
 * Does NOT require smart mode -- formatting doesn't need indexes.
 */
class ReformatCodeTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<ReformatCodeTool>()
    }

    override val name = ToolNames.REFORMAT_CODE

    override val description = """
        Reformat code in a file according to the project's code style settings (.editorconfig, IDE code style). Equivalent to the IDE's "Reformat Code" action (Ctrl+Alt+L / Cmd+Opt+L). Supports undo (Ctrl+Z).

        By default optimizes imports, but does not rearrange code members unless explicitly requested. Use optimizeImports=false and rearrangeCode=false to only reformat whitespace/indentation.

        Respects: .editorconfig, project code style, language-specific formatting rules.

        Returns: success status, affected file, and description of operations performed.

        Parameters: file (required), startLine/endLine (optional inclusive range), optimizeImports (default: true), rearrangeCode (default: false).

        Example: {"file": "src/MyClass.java"}
        Example: {"file": "src/MyClass.java", "startLine": 10, "endLine": 50, "optimizeImports": false}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file()
        .intProperty(ParamNames.START_LINE, "Start line for partial formatting (1-based, inclusive). If provided, endLine is also required.")
        .intProperty(ParamNames.END_LINE, "End line for partial formatting (1-based, inclusive). If provided, startLine is also required.")
        .booleanProperty(ParamNames.OPTIMIZE_IMPORTS, "Optimize imports (remove unused, organize). Default: true.")
        .booleanProperty(ParamNames.REARRANGE_CODE, "Rearrange code members according to arrangement rules. Default: false.")
        .build()

    /**
     * Data class holding validated reformat parameters from Phase 1.
     */
    private data class ReformatValidation(
        val psiFile: PsiFile? = null,
        val textRange: TextRange? = null,
        val initialText: String? = null,
        val error: String? = null
    )

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val file = requiredStringArg(arguments, ParamNames.FILE).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: file")
        }
        val startLine = arguments[ParamNames.START_LINE]?.jsonPrimitive?.int
        val endLine = arguments[ParamNames.END_LINE]?.jsonPrimitive?.int
        val optimizeImports = arguments[ParamNames.OPTIMIZE_IMPORTS]?.jsonPrimitive?.boolean ?: true
        val rearrangeCode = arguments[ParamNames.REARRANGE_CODE]?.jsonPrimitive?.boolean ?: false

        // Validate startLine/endLine pairing
        if ((startLine != null) != (endLine != null)) {
            return createErrorResult("Both startLine and endLine must be provided together, or neither.")
        }
        if (startLine != null && endLine != null) {
            if (startLine < 1) return createErrorResult("startLine must be >= 1")
            if (endLine < 1) return createErrorResult("endLine must be >= 1")
            if (endLine < startLine) return createErrorResult("endLine must be >= startLine")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 1: BACKGROUND - Resolve file and validate (suspending read action)
        // ═══════════════════════════════════════════════════════════════════════
        val virtualFile = resolveFile(project, file)
            ?: return createErrorResult("File not found: $file")

        val validation = suspendingReadAction {
            validateAndPrepare(project, virtualFile, file, startLine, endLine)
        }

        if (validation.error != null) {
            return createErrorResult(validation.error)
        }

        val psiFile = validation.psiFile
            ?: return createErrorResult("Failed to resolve file: $file")
        val textRange = validation.textRange
        val initialText = validation.initialText
            ?: return createErrorResult("Failed to snapshot file before reformat: $file")

        val operationsRun = buildList {
            add("reformat")
            if (optimizeImports) add("optimize_imports")
            if (rearrangeCode) add("rearrange_code")
        }
        val skippedOperations = buildList {
            if (!optimizeImports) add("optimize_imports_skipped")
            if (!rearrangeCode) add("rearrange_code_skipped")
        }

        // ═══════════════════════════════════════════════════════════════════════
        // PHASE 2: EDT - Execute reformat using processor chaining
        // ═══════════════════════════════════════════════════════════════════════
        var errorMessage: String? = null

        edtAction {
            try {
                executeReformat(project, psiFile, textRange, optimizeImports, rearrangeCode)
            } catch (e: Exception) {
                LOG.warn("Reformat failed for $file", e)
                errorMessage = e.message ?: "Unknown error during reformat"
            }
        }

        // Commit and save outside EDT block — commitDocuments uses
        // TransactionGuard.submitTransactionAndWait for write-safe context
        if (errorMessage == null) {
            commitDocuments(project)
            edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        }

        return if (errorMessage != null) {
            createErrorResult("Reformat failed: $errorMessage")
        } else {
            val finalText = suspendingReadAction {
                PsiDocumentManager.getInstance(project).getDocument(psiFile)?.text ?: psiFile.text
            }
            val changed = finalText != initialText
            val status = if (changed) "success" else "no_op"
            val changesCount = if (changed) 1 else 0
            val rangeNote = if (startLine != null && endLine != null) {
                " (lines $startLine-$endLine)"
            } else ""
            val operationsNote = operationsRun.joinToString(", ")
            val skippedNote = if (skippedOperations.isEmpty()) {
                ""
            } else {
                "; skipped ${skippedOperations.joinToString(", ")}"
            }
            val action = if (changed) "Reformatted" else "No formatting changes required for"

            createJsonResult(
                RefactoringResult(
                    success = true,
                    affectedFiles = listOf(file),
                    changesCount = changesCount,
                    message = "$action $file$rangeNote; ran $operationsNote$skippedNote",
                    status = status,
                    verification = MutationVerification(
                        status = status,
                        checksRun = operationsRun,
                        warnings = skippedOperations
                    )
                )
            )
        }
    }

    /**
     * Validates parameters and resolves the PSI file and text range.
     * Runs in a read action (background thread).
     */
    private fun validateAndPrepare(
        project: Project,
        virtualFile: VirtualFile,
        file: String,
        startLine: Int?,
        endLine: Int?
    ): ReformatValidation {
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            ?: return ReformatValidation(error = "Cannot parse file: $file")

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return ReformatValidation(error = "Cannot get document for file: $file")

        // Calculate text range if line range specified
        val textRange = if (startLine != null && endLine != null) {
            val lineCount = document.lineCount
            if (startLine > lineCount) {
                return ReformatValidation(
                    error = "startLine ($startLine) exceeds file line count ($lineCount)"
                )
            }
            if (endLine > lineCount) {
                return ReformatValidation(
                    error = "endLine ($endLine) exceeds file line count ($lineCount)"
                )
            }

            val startOffset = document.getLineStartOffset(startLine - 1)
            val endOffset = document.getLineEndOffset(endLine - 1)
            TextRange(startOffset, endOffset)
        } else null

        return ReformatValidation(psiFile = psiFile, textRange = textRange, initialText = document.text)
    }

    /**
     * Executes the reformat operation using IntelliJ's processor chaining.
     * Must run on EDT.
     *
     * Uses [AbstractLayoutCodeProcessor.runWithoutProgress] instead of
     * [AbstractLayoutCodeProcessor.run] because `run()` dispatches via `ProgressManager`
     * as a background task in non-headless mode, returning before processing completes.
     * `runWithoutProgress()` executes synchronously, ensuring the document is fully
     * updated before we commit and save. Undo (Ctrl+Z) works automatically.
     */
    private fun executeReformat(
        project: Project,
        psiFile: PsiFile,
        textRange: TextRange?,
        optimizeImports: Boolean,
        rearrangeCode: Boolean
    ) {
        var processor: AbstractLayoutCodeProcessor = if (textRange != null) {
            ReformatCodeProcessor(psiFile, arrayOf(textRange))
        } else {
            ReformatCodeProcessor(psiFile, false)
        }

        if (optimizeImports) {
            processor = OptimizeImportsProcessor(processor)
        }
        if (rearrangeCode) {
            processor = RearrangeCodeProcessor(processor)
        }

        processor.runWithoutProgress()
    }
}
