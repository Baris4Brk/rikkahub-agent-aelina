package me.rerere.rikkahub.learning.storage

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningSchemaHistoryContractTest {
    @Test
    fun `recovered v2 is the exact pre-policy v3 schema with a Room-derived identity`() {
        val v2 = readSchema(2)
        val v3 = readSchema(3)
        val v2Database = v2.getValue("database").jsonObject
        val v3Database = v3.getValue("database").jsonObject

        assertEquals(2, v2Database.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(3, v3Database.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(
            v2Database.getValue("identityHash").jsonPrimitive.content,
            roomDatabaseIdentity(v2Database),
        )
        assertRoomSetupQueryUsesExportedIdentity(v2Database)
        assertEquals(
            v3Database.getValue("identityHash").jsonPrimitive.content,
            roomDatabaseIdentity(v3Database),
        )
        assertRoomSetupQueryUsesExportedIdentity(v3Database)

        val v2Entities = entitiesByTable(v2Database)
        val v3Entities = entitiesByTable(v3Database)
        assertEquals(V3_POLICY_TABLES, v3Entities.keys - v2Entities.keys)
        assertEquals(emptySet<String>(), v2Entities.keys - v3Entities.keys)
        v2Entities.forEach { (table, entity) ->
            assertEquals("Historical common entity drifted: $table", entity, v3Entities[table])
        }

        val statements = LEARNING_V3_SCHEMA_SQL.map(String::trimStart)
        assertTrue(statements.all { sql ->
            sql.startsWith("CREATE TABLE IF NOT EXISTS") ||
                sql.startsWith("CREATE INDEX IF NOT EXISTS") ||
                sql.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS")
        })
        assertEquals(
            V3_POLICY_TABLES,
            statements.mapNotNull { CREATE_TABLE.find(it)?.groupValues?.get(1) }.toSet(),
        )
    }

    @Test
    fun `frozen v8 remains the exact predecessor of additive utility-only v9`() {
        val v8Database = readSchema(8).getValue("database").jsonObject
        val v9Database = readSchema(9).getValue("database").jsonObject
        assertEquals(8, v8Database.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(9, v9Database.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(
            v8Database.getValue("identityHash").jsonPrimitive.content,
            roomDatabaseIdentity(v8Database),
        )
        assertRoomSetupQueryUsesExportedIdentity(v8Database)
        assertEquals(
            v9Database.getValue("identityHash").jsonPrimitive.content,
            roomDatabaseIdentity(v9Database),
        )
        assertRoomSetupQueryUsesExportedIdentity(v9Database)

        val v8Entities = entitiesByTable(v8Database)
        val v9Entities = entitiesByTable(v9Database)
        val v8Tables = v8Entities.keys
        assertTrue(v8Tables.intersect(V9_OBSERVED_UTILITY_TABLES).isEmpty())
        assertEquals(V9_OBSERVED_UTILITY_TABLES, v9Entities.keys - v8Tables)
        assertEquals(emptySet<String>(), v8Tables - v9Entities.keys)
        v8Entities.forEach { (table, entity) ->
            assertEquals("Frozen v8 entity drifted in v9: $table", entity, v9Entities[table])
        }
        assertEquals(V9_OBSERVED_UTILITY_TABLES, migrationTables(LEARNING_V9_SCHEMA_SQL))
        assertEquals(10, LEARNING_V9_SCHEMA_SQL.size)
        assertTrue(LEARNING_V9_SCHEMA_SQL.all { statement ->
            statement.trimStart().startsWith("CREATE TABLE IF NOT EXISTS") ||
                statement.trimStart().startsWith("CREATE INDEX IF NOT EXISTS") ||
                statement.trimStart().startsWith("CREATE UNIQUE INDEX IF NOT EXISTS")
        })
    }

    private fun readSchema(version: Int): JsonObject {
        val relative =
            "schemas/me.rerere.rikkahub.learning.storage.LearningDatabase/$version.json"
        val file = sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)
            ?: error("Missing LearningDatabase/$version.json")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun entitiesByTable(database: JsonObject): Map<String, JsonElement> =
        database.getValue("entities").jsonArray.associateBy { entity ->
            entity.jsonObject.getValue("tableName").jsonPrimitive.content
        }

    private fun assertRoomSetupQueryUsesExportedIdentity(database: JsonObject) {
        val identity = database.getValue("identityHash").jsonPrimitive.content
        val identityQueries = database.getValue("setupQueries").jsonArray.map {
            it.jsonPrimitive.content
        }.filter { "INSERT OR REPLACE INTO room_master_table" in it }
        assertEquals(1, identityQueries.size)
        assertTrue(identityQueries.single().contains("VALUES(42, '$identity')"))
    }

    /** Room 2.8 SchemaIdentityKey, independently mirrored for immutable schema verification. */
    private fun roomDatabaseIdentity(database: JsonObject): String {
        val entityKeys = database.getValue("entities").jsonArray.map { entityIdentity(it.jsonObject) }
        val viewKeys = database["views"].arrayOrEmpty().map { view ->
            view.jsonObject.getValue("createSql").jsonPrimitive.content
        }
        return md5((entityKeys + viewKeys).englishSorted().joinToString("") { "$it$SEPARATOR" })
    }

    private fun entityIdentity(entity: JsonObject): String {
        val primaryKey = entity.getValue("primaryKey").jsonObject
        val parts = buildList {
            add(entity.getValue("tableName").jsonPrimitive.content)
            add(primaryKeyIdentity(primaryKey))
            addAll(entity.getValue("fields").jsonArray.map { fieldIdentity(it.jsonObject) }
                .englishSorted())
            addAll(entity["indices"].arrayOrEmpty().map { indexIdentity(it.jsonObject) }
                .englishSorted())
            addAll(entity["foreignKeys"].arrayOrEmpty().map { foreignKeyIdentity(it.jsonObject) }
                .englishSorted())
        }
        return md5(parts.joinToString("") { "$it$SEPARATOR" })
    }

    private fun fieldIdentity(field: JsonObject): String = buildString {
        append(field.getValue("columnName").jsonPrimitive.content)
        append('-')
        append(field["affinity"]?.jsonPrimitive?.content ?: "TEXT")
        append('-')
        append(field["notNull"]?.jsonPrimitive?.boolean ?: false)
        field["defaultValue"]?.jsonPrimitive?.content?.let { defaultValue ->
            append("-defaultValue=")
            append(defaultValue)
        }
    }

    private fun primaryKeyIdentity(primaryKey: JsonObject): String {
        val autoGenerate = primaryKey["autoGenerate"]?.jsonPrimitive?.boolean ?: false
        val columns = primaryKey.getValue("columnNames").jsonArray
            .joinToString(", ") { it.jsonPrimitive.content }
        return "$autoGenerate-[$columns]"
    }

    private fun indexIdentity(index: JsonObject): String = buildString {
        append(index["unique"]?.jsonPrimitive?.boolean ?: false)
        append('-')
        append(index.getValue("name").jsonPrimitive.content)
        append('-')
        append(index.getValue("columnNames").jsonArray.joinToString(",") {
            it.jsonPrimitive.content
        })
        val orders = index["orders"].arrayOrEmpty()
        if (orders.isNotEmpty()) {
            append('-')
            append(orders.joinToString(",") { it.jsonPrimitive.content })
        }
    }

    private fun foreignKeyIdentity(foreignKey: JsonObject): String = listOf(
        foreignKey.getValue("table").jsonPrimitive.content,
        foreignKey.getValue("referencedColumns").jsonArray.joinToString(",") {
            it.jsonPrimitive.content
        },
        foreignKey.getValue("columns").jsonArray.joinToString(",") {
            it.jsonPrimitive.content
        },
        foreignKey.getValue("onDelete").jsonPrimitive.content,
        foreignKey.getValue("onUpdate").jsonPrimitive.content,
        (foreignKey["deferred"]?.jsonPrimitive?.boolean ?: false).toString(),
    ).joinToString("-")

    private fun List<String>.englishSorted(): List<String> =
        sortedWith(compareBy { it.lowercase(Locale.ENGLISH) })

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val SEPARATOR = "?:?"
        val V3_POLICY_TABLES = setOf(
            "learning_policies",
            "policy_evidence",
            "policy_revisions",
            "policy_lineage",
        )
        val CREATE_TABLE = Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`")
        val V9_OBSERVED_UTILITY_TABLES = setOf(
            "learning_observed_utility_assignments",
            "learning_observed_utility_outcomes",
            "learning_observed_utility_evaluation_receipts",
        )
    }
}

private fun migrationTables(statements: List<String>): Set<String> = statements.mapNotNull { sql ->
    Regex("CREATE TABLE IF NOT EXISTS `([^`]+)`").find(sql)?.groupValues?.get(1)
}.toSet()

private fun JsonElement?.arrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
