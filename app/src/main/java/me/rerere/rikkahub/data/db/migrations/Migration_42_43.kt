package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Makes Memory V2 provenance and relation state durable. Legacy relation rows are deliberately
 * fail-closed: only links whose endpoints still exist in one scope are scoped, and every migrated
 * link remains invalidated until a reviewed relation recreates it with endpoint revisions/hashes.
 */
val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memory_captures` ADD COLUMN `payload_purged_at_ms` INTEGER")

        db.execSQL("ALTER TABLE `memory_candidates` ADD COLUMN `batch_id` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_candidates_batch_id` " +
                "ON `memory_candidates` (`batch_id`)",
        )

        db.execSQL("ALTER TABLE `memory_evidence` ADD COLUMN `relation_candidate_id` TEXT")
        db.execSQL("ALTER TABLE `memory_evidence` ADD COLUMN `link_id` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_evidence_relation_candidate_id` " +
                "ON `memory_evidence` (`relation_candidate_id`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_evidence_link_id` " +
                "ON `memory_evidence` (`link_id`)",
        )

        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `scope_id` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `created_by_assistant_id` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `source_candidate_id` TEXT")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `target_candidate_id` TEXT")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `source_expected_revision` INTEGER")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `target_expected_revision` INTEGER")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `resolved_link_id` TEXT")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `resolution_error` TEXT")
        db.execSQL("ALTER TABLE `memory_relation_candidates` ADD COLUMN `updated_at_ms` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "UPDATE `memory_relation_candidates` SET `status` = 'INVALIDATED', " +
                "`resolution_error` = 'MIGRATION_UNVERIFIED', `updated_at_ms` = `created_at_ms`",
        )
        db.execSQL("DROP INDEX IF EXISTS `index_memory_relation_candidates_status`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_scope_id_status_created_at_ms` " +
                "ON `memory_relation_candidates` (`scope_id`, `status`, `created_at_ms`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_source_candidate_id` ON `memory_relation_candidates` (`source_candidate_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_target_candidate_id` ON `memory_relation_candidates` (`target_candidate_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_relation_candidates_resolved_link_id` ON `memory_relation_candidates` (`resolved_link_id`)")

        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `scope_id` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `lifecycle_status` TEXT NOT NULL DEFAULT 'ACTIVE'")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `source_revision` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `target_revision` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `source_semantic_hash` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `target_semantic_hash` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `relation_candidate_id` TEXT")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `updated_at_ms` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `invalidated_at_ms` INTEGER")
        db.execSQL("ALTER TABLE `memory_links` ADD COLUMN `invalidation_reason` TEXT")
        db.execSQL(
            "UPDATE `memory_links` SET `scope_id` = COALESCE((" +
                "SELECT s.`assistant_id` FROM `MemoryEntity` s " +
                "INNER JOIN `MemoryEntity` t ON t.`id` = `memory_links`.`target_memory_id` " +
                "WHERE s.`id` = `memory_links`.`source_memory_id` " +
                "AND s.`assistant_id` = t.`assistant_id`), ''), " +
                "`lifecycle_status` = 'INVALIDATED', `updated_at_ms` = `created_at_ms`, " +
                "`invalidated_at_ms` = `created_at_ms`, " +
                "`invalidation_reason` = 'MIGRATION_UNVERIFIED'",
        )
        db.execSQL("DROP INDEX IF EXISTS `index_memory_links_source_memory_id_target_memory_id_relation_type`")
        db.execSQL("DROP INDEX IF EXISTS `index_memory_links_target_memory_id`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_links_scope_id_source_memory_id_target_memory_id_relation_type` " +
                "ON `memory_links` (`scope_id`, `source_memory_id`, `target_memory_id`, `relation_type`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_links_scope_id_source_memory_id_lifecycle_status` " +
                "ON `memory_links` (`scope_id`, `source_memory_id`, `lifecycle_status`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_links_scope_id_target_memory_id_lifecycle_status` " +
                "ON `memory_links` (`scope_id`, `target_memory_id`, `lifecycle_status`)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_links_relation_candidate_id` ON `memory_links` (`relation_candidate_id`)")

        db.execSQL("ALTER TABLE `memory_revisions` ADD COLUMN `reason_code` TEXT")
        db.execSQL("ALTER TABLE `memory_revisions` ADD COLUMN `cause_memory_id` INTEGER")
        db.execSQL("ALTER TABLE `memory_revisions` ADD COLUMN `cause_link_id` TEXT")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_link_revisions` (" +
                "`id` TEXT NOT NULL, `link_id` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
                "`operation` TEXT NOT NULL, `before_snapshot_json` TEXT, `after_snapshot_json` TEXT, " +
                "`actor` TEXT NOT NULL, `relation_candidate_id` TEXT, `reason_code` TEXT, " +
                "`created_at_ms` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_link_revisions_link_id_revision` ON `memory_link_revisions` (`link_id`, `revision`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_link_revisions_link_id_created_at_ms` ON `memory_link_revisions` (`link_id`, `created_at_ms`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_link_revisions_relation_candidate_id` ON `memory_link_revisions` (`relation_candidate_id`)")
    }
}
