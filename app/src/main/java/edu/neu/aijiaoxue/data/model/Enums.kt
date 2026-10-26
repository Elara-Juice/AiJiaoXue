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

/** 建议的生成来源；与教师采纳/修改的处置方式分开保存。 */
enum class SuggestionOrigin(val label: String) {
    RULE_BASED("规则辅助建议"),
    AI_GENERATED("AI候选建议"),
}

/** AI 分析类型（Sprint 2）。 */
enum class AiAnalysisType(val label: String) {
    BOPPPS("教学环节"),
    INTERACTION("师生互动"),
    ENGAGEMENT("学生参与"),
}

/**
 * Sprint 3 US45：可比较的课堂指标。单位、分母与口径版本见 Rules.kt 的 [MetricRules]，
 * 这里只列指标本身，不在页面里另起一套口径。
 */
enum class MetricKey(val label: String) {
    /** 学生参与率：由参与状态记录统计，单位为百分比。 */
    ENGAGEMENT_RATE("学生参与率"),
    /** 师生互动密度：单位时间内的互动次数，反映课堂节奏。 */
    INTERACTION_DENSITY("师生互动密度"),
    /** 督导评价总分：来自已提交的正式评价。 */
    EVALUATION_TOTAL("督导评价总分"),
}

/**
 * Sprint 3 US24：多源参与分析的数据来源。至少两类来源同时存在才允许形成结论，
 * 来源名称用于在界面上标注每条结论出自哪类记录。
 */
enum class DataSourceKind(val label: String) {
    ENGAGEMENT_RECORD("参与状态记录"),
    INTERACTION_EVENT("互动事件"),
    MATERIAL("课堂材料"),
    EVALUATION("正式评价"),
}

/** Sprint 3 US25：参与异常的类型。 */
enum class AnomalyType(val label: String) {
    /** 连续多条记录为低头/走神。 */
    DISTRACTION_RUN("连续走神"),
    /** 记录到的参与比例低于阈值。 */
    LOW_ENGAGEMENT("参与比例偏低"),
    /** 一段时间内没有任何互动事件。 */
    NO_INTERACTION("长时间无互动"),
}

/**
 * Sprint 3 US46：重点问题改善核验结论。“措施已完成”只说明执行了措施，
 * 不等于问题已改善，结论只能由人工核验后写入。
 */
enum class VerificationVerdict(val label: String) {
    IMPROVED("问题已改善"),
    NOT_IMPROVED("问题未改善"),
    CONTINUE_OBSERVING("继续观察"),
}
