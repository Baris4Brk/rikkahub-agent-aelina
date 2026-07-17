package me.rerere.workspace

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WorkspaceProcessPersistence(
    private val workspaceManager: WorkspaceManager,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    },
) {
    data class StoredDefinition(
        val workspaceRoot: String,
        val definition: WorkspaceProcessDefinition,
    )

    fun processDirectory(workspaceRoot: String, processId: String): File =
        File(workspaceManager.managedProcessesDir(workspaceRoot), requireValidWorkspaceProcessId(processId))

    fun definitionFile(workspaceRoot: String, processId: String): File =
        File(processDirectory(workspaceRoot, processId), DEFINITION_FILE)

    fun stdoutFile(workspaceRoot: String, processId: String): File =
        File(processDirectory(workspaceRoot, processId), STDOUT_FILE)

    fun stderrFile(workspaceRoot: String, processId: String): File =
        File(processDirectory(workspaceRoot, processId), STDERR_FILE)

    fun processTempDirectory(workspaceRoot: String, processId: String): File =
        File(processDirectory(workspaceRoot, processId), PROOT_TEMP_DIR)

    fun write(workspaceRoot: String, definition: WorkspaceProcessDefinition) {
        val target = definitionFile(workspaceRoot, definition.id)
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Unable to create managed process directory" }
        val temporary = File(target.parentFile, "$DEFINITION_FILE.tmp-${clockMillis()}")
        val bytes = json.encodeToString(definition).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            replaceAtomically(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun read(workspaceRoot: String, processId: String): WorkspaceProcessDefinition? =
        readFile(workspaceRoot, definitionFile(workspaceRoot, processId))

    fun scan(validWorkspaces: Map<String, String>): List<StoredDefinition> = buildList {
        validWorkspaces.forEach { (workspaceId, workspaceRoot) ->
            val root = workspaceManager.managedProcessesDir(workspaceRoot)
            root.listFiles()
                ?.asSequence()
                ?.filter(File::isDirectory)
                ?.map { File(it, DEFINITION_FILE) }
                ?.filter(File::isFile)
                ?.forEach { file ->
                    val definition = readFile(workspaceRoot, file) ?: return@forEach
                    if (definition.workspaceId == workspaceId) {
                        add(StoredDefinition(workspaceRoot, definition))
                    }
                }
        }
    }

    private fun readFile(workspaceRoot: String, file: File): WorkspaceProcessDefinition? {
        return try {
            json.decodeFromString<WorkspaceProcessDefinition>(file.readText(Charsets.UTF_8))
        } catch (_: SerializationException) {
            quarantineCorruptDefinition(file)
            null
        } catch (_: IllegalArgumentException) {
            quarantineCorruptDefinition(file)
            null
        }
    }

    private fun quarantineCorruptDefinition(file: File) {
        val quarantine = File(file.parentFile, "$DEFINITION_FILE.corrupt-${clockMillis()}")
        runCatching { Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            .recoverCatching { file.renameTo(quarantine) }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val DEFINITION_FILE = "definition.json"
        private const val STDOUT_FILE = "stdout.log"
        private const val STDERR_FILE = "stderr.log"
        private const val PROOT_TEMP_DIR = "proot-tmp"
    }
}
