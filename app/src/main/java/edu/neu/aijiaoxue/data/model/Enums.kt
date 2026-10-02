package edu.neu.aijiaoxue.data.model

/**
 * 全部业务枚举集中定义（需求 NF-10）。
 * 数据库存英文枚举名（Room 默认按 name 存 TEXT），界面显示 label。
 * 新增取值只能追加，已有取值不得改名，否则旧数据无法读取。
 */

/** 用户角色，一个账号只对应一个角色（F0）。 */
enum class Role(val label: String) {
    ADMIN("教学管理人员"),
    SUPERVISOR("教学督导"),
    TEACHER("任课教师"),
}

/** 督导任务状态（US06）。“已逾期”由 [isTaskOverdue] 计算，不入库。 */
enum class TaskStatus(val label: String) {
    NOT_STARTED("待开始"),
    IN_PROGRESS("进行中"),
    PENDING_EVALUATION("待评价"),
    COMPLETED("已完成"),
}

/** 督导类型（US04）。 */
enum class SupervisionType(val label: String) {
    REGULAR("常规督导"),
    SPECIAL("专项督导"),
    FOLLOW_UP("跟踪复评"),
}

/** 督导安排状态（US05）。任务被创建并关联后变为 CLAIMED。 */
enum class ArrangementStatus(val label: String) {
    PENDING("待督导"),
    CLAIMED("已认领"),
    DONE("已完成"),
}

/** 课堂材料类型（US08、US09）。 */
enum class MaterialType(val label: String) {
    AUDIO("音频"),
    PPT("PPT"),
    BLACKBOARD("板书"),
    OTHER("其他"),
}

/** 互动事件类型（US12）。 */
enum class InteractionType(val label: String) {
    TEACHER_QUESTION("教师提问"),
    STUDENT_ANSWER("学生回答"),
    STUDENT_QUESTION("学生主动提问"),
    GROUP_DISCUSSION("小组讨论"),
    OTHER("其他"),
}

/** 学生参与状态类型（US13）。 */
enum class EngagementType(val label: String) {
    LISTENING("抬头听讲"),
    DISCUSSING("讨论"),
    PRACTICING("实践操作"),
    DISTRACTED("低头/走神"),
    OTHER("其他"),
}

/** 评价维度及权重（US29），权重合计 100。 */
enum class EvalDimension(val label: String, val weight: Int) {
    CONTENT("教学内容", 30),
    DESIGN("教学设计", 25),
    INTERACTION("课堂互动", 25),
    ENGAGEMENT("学生参与", 20),
}

/** 评价等级（US29），阈值见 [EvalRules.gradeOf]。 */
enum class EvalGrade(val label: String) {
    EXCELLENT("优秀"),
    GOOD("良好"),
    PASS("合格"),
    NEEDS_IMPROVEMENT("待改进"),
}

/** 评价状态（US29、US30）。 */
enum class EvalStatus(val label: String) {
    DRAFT("草稿"),
    SUBMITTED("已提交"),
}

/** 改进事项来源（US36）。 */
enum class ItemSource(val label: String) {
    SUPERVISOR("督导问题"),
    TEACHER("教师自选"),
}

/** 改进事项优先级（US36）。 */
enum class Priority(val label: String) {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低"),
}

/** 改进计划状态（US41）。Sprint 1 确认后不可修改。 */
enum class PlanStatus(val label: String) {
    DRAFT("草稿"),
    CONFIRMED("已确认"),
}

/** 改进措施来源（US41），为 Sprint 2 人在回路追溯预留。 */
enum class MeasureSource(val label: String) {
    ADOPTED("采纳督导建议"),
    MODIFIED("修改督导建议"),
    ADDED("教师新增"),
}

/** 改进待办状态（US42）。“已逾期”由 [isMeasureOverdue] 计算，不入库。 */
enum class MeasureStatus(val label: String) {
    NOT_STARTED("未开始"),
    IN_PROGRESS("进行中"),
    DONE("已完成"),
}
