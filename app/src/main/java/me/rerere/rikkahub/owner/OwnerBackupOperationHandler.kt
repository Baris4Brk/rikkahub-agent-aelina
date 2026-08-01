package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.sync.LocalBackupFacade
import me.rerere.rikkahub.privilege.PrivilegedSessionContext

/** Streams complete local archives into the private managed-file library without a SAF prompt. */
class OwnerBackupOperationHandler(
    private val backups: LocalBackupFacade,
    private val files: FilesManager,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.BACKUP_STORAGE && action.type == "backup_local_export"

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation = if (action.arguments.isEmpty()) {
        OwnerActionValidation(true, "OWNER_BACKUP_ACTION_VALID", "Local backup export validated.")
    } else {
        invalid("OWNER_UNSUPPORTED_FIELD", "backup_local_export does not accept arguments.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        val entity = backups.exportManaged()
        OwnerAppliedAction(
            OwnerActionResult(
                index, action.type, true, "LOCAL_BACKUP_EXPORTED",
                "Complete local backup saved in the private managed-file library.",
                buildJsonObject {
                    put("managed_file_id", entity.id); put("display_name", entity.displayName.take(160))
                    put("size_bytes", entity.sizeBytes)
                },
            ),
            Receipt(entity.id),
        )
    }.getOrElse { failure(index, action, "LOCAL_BACKUP_EXPORT_FAILED", "Local backup export failed.") }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val receipt = applied.compensationReceipt as? Receipt
            ?: return invalid("LOCAL_BACKUP_RECEIPT_MISSING", "Backup receipt is missing.")
        val entity = files.get(receipt.managedFileId)
        val valid = entity != null && files.getFile(entity).let { it.isFile && it.length() == entity.sizeBytes && it.length() > 0 }
        return if (valid) OwnerActionValidation(true, "LOCAL_BACKUP_VERIFIED", "Managed backup was read back from private storage.")
        else invalid("LOCAL_BACKUP_VERIFY_FAILED", "Managed backup is missing or incomplete.")
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        val receipt = applied.compensationReceipt as? Receipt
            ?: return OwnerCompensationResult(false, "LOCAL_BACKUP_RECEIPT_MISSING")
        val entity = files.get(receipt.managedFileId)
            ?: return OwnerCompensationResult(true, "LOCAL_BACKUP_ALREADY_REMOVED")
        return OwnerCompensationResult(files.deleteManagedFile(entity), "LOCAL_BACKUP_REMOVED")
    }

    private fun failure(index: Int, action: OwnerAction, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, false, code, message))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private data class Receipt(val managedFileId: Long)
}
