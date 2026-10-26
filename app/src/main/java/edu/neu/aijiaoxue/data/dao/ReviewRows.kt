package edu.neu.aijiaoxue.data.dao

/**
 * Sprint 3（李奕萱）：复评关联列表行。
 * 只带展示需要的字段，权限仍在 SQL 里判断（AGENTS.md 硬性规则 6）。
 */
data class ReviewLinkRow(
    val id: Long,
    val originTaskId: Long,
    val followUpTaskId: Long,
    val originIssueSnapshot: String,
    val originDimension: String?,
    val courseName: String,
    val teacherName: String,
    val originDate: String,
    val originPeriod: String,
    val followUpDate: String,
    val followUpPeriod: String,
    val createdAt: Long,
)

/**
 * 复评关联列表里“原课堂候选”的展示行。
 * 与 [ReviewLinkRow] 一样在 SQL 层判断权限。
 */
data class ReviewTaskCandidateRow(
    val id: Long,
    val code: String,
    val courseId: Long,
    val courseName: String,
    val supervisionDate: String,
    val period: String,
    val chapter: String?,
)
