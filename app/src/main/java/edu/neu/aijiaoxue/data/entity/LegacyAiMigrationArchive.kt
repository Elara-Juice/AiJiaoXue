package edu.neu.aijiaoxue.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 旧 AI 表缺少约束时留下的异常原始记录。仅供迁移诊断，不参与审核、发布或教师建议。
 * 不绑定业务外键，避免清理原分析时连诊断证据也被删除；rawRowJson 保留修复前全部字段。
 */
@Entity(
    tableName = "legacy_ai_migration_archives",
    indices = [Index(value = ["sourceVersion", "sourceTable", "sourceId"], unique = true)],
)
data class LegacyAiMigrationArchive(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceVersion: Int,
    val sourceTable: String,
    val sourceId: Long,
    val reason: String,
    val rawRowJson: String,
    val archivedAt: Long,
)
