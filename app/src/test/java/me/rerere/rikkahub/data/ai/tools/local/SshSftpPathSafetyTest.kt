package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.db.dao.SshHostDao
import me.rerere.rikkahub.data.db.entity.SshHostEntity
import me.rerere.rikkahub.data.repository.SshHostRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SshSftpPathSafetyTest {

    @Test fun `ssh download cannot overwrite core second user data`() {
        val localPath =
            "/data/user/0/${BuildConfig.APPLICATION_ID}/databases/rikka_hub"
        val result = Json.parseToJsonElement(
            execTool(
                sshDownloadTool(NULL_CONTEXT, SshHostRepository(neverUsedDao)),
                """{"name":"unused","remote_path":"/remote/file","local_path":"$localPath"}""",
            )
        ).jsonObject

        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `ssh upload cannot exfiltrate core second user data`() {
        val localPath =
            "/data/user/0/${BuildConfig.APPLICATION_ID}/databases/rikka_hub"
        val result = Json.parseToJsonElement(
            execTool(
                sshUploadTool(NULL_CONTEXT, SshHostRepository(neverUsedDao)),
                """{"name":"unused","local_path":"$localPath","remote_path":"/remote/copy"}""",
            )
        ).jsonObject

        assertEquals("path_blocked", result["error"]?.jsonPrimitive?.content)
    }

    private val neverUsedDao = object : SshHostDao {
        override suspend fun getAll(): List<SshHostEntity> = error("must not reach repository")
        override suspend fun getByName(name: String): SshHostEntity? =
            error("must not reach repository")
        override suspend fun upsert(host: SshHostEntity) = error("must not reach repository")
        override suspend fun delete(host: SshHostEntity) = error("must not reach repository")
        override suspend fun deleteByName(name: String) = error("must not reach repository")
    }
}
