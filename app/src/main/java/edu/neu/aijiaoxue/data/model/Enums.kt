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
    CONTENT("课堂知识点"),
    SYLLABUS_ALIGNMENT("大纲内容对齐"),
    GOAL_ACTIVITY("目标活动对应"),
    MATERIAL_SUPPORT("课件板书支撑"),
    BOPPPS("教学环节"),
    INTERACTION("师生互动"),
    ENGAGEMENT("学生参与"),
    VERBAL_REPETITION("口头表达"),
    TEACHING_TRANSITION("教学环节衔接"),
}

/** 整改：处理状态与教学结论分开；不足输入不能伪装成课堂事实。 */
enum class ResultState { SUPPORTED, PARTIAL, INSUFFICIENT_INPUT, UNREADABLE, FAILED }
enum class SourceMode { CLOUD_ASR, CLOUD_MODEL, DETERMINISTIC_RULE, MANUAL, LEGACY }
enum class AnalysisOperation(val label: String) {
    LEGACY("历史分析"), TRANSCRIBE("音频转写"), IMAGE_TEXT("图片文字识别"),
    CONTENT("课堂知识点"), SYLLABUS_ALIGNMENT("大纲对齐"), BOPPPS("BOPPPS结构"),
    GOAL_ACTIVITY("目标活动对应"), TRANSITION("教学衔接"), MATERIAL_SUPPORT("材料支撑度"),
    INTERACTION_STATS("互动统计"), PARTICIPATION("参与对照"), EXPRESSION_STATS("表达统计")
}
enum class JobState { QUEUED, RUNNING, SUCCEEDED, PARTIAL, FAILED, CANCEL_REQUESTED, CANCELLED, STALE_INPUT, EXPIRED }
enum class AnalysisRunState { PREPARED, RUNNING, PAUSED, WAITING_REVIEW, WAITING_DECISION, COMPLETED, PARTIAL, CANCEL_REQUESTED, CANCELLED }
enum class AnalysisStepState { PLANNED, BLOCKED, RUNNING, SUCCEEDED, PARTIAL, FAILED, SKIPPED, REUSED, CANCEL_REQUESTED, CANCELLED, STALE_INPUT }
enum class AnalysisViewSection { PREPARATION, PROGRESS, RESULTS }

/** Computed presentation states; never manually persisted as task states. */
enum class SupervisorTab(val label: String) { WORKBENCH("工作台"), TASKS("督导任务"), TRACKING("改进跟踪"), PROFILE("我的") }
enum class TaskWorkspaceTab(val label: String) { OVERVIEW("概况"), CAPTURE("采集"), ANALYSIS("分析核验"), REPORT("报告"), IMPROVEMENT("关联改进") }
enum class TrackingStage(val label: String) { AWAITING_PLAN("待教师响应"), IMPLEMENTING("实施中"), AWAITING_REVIEW("待复评"), CLOSED("已关闭") }
enum class ConnectionCheckState { PASSED, FAILED, NOT_CHECKED }
enum class UiMutationStatus { IDLE, SAVING, SAVED, FAILED, CONFLICT }
enum class InputWorkspaceTab { COURSE, TRANSCRIPT, FACTS }
enum class EvidencePlaybackStatus { LOADING, READY, PLAYING, PAUSED, FINISHED, FAILED }
enum class PhaseStatus { DRAFT, CONFIRMED }
enum class ActivityKind { INTRODUCTION, LECTURE, DISCUSSION, PRACTICE, SUMMARY, OTHER }
enum class SpeakerRole { TEACHER, STUDENT, UNKNOWN }
enum class TimeMappingQuality { EXACT, ESTIMATED, UNKNOWN }
enum class EvidenceKind { MATERIAL, INTERACTION_EVENT, ENGAGEMENT_RECORD, TRANSCRIPT_SEGMENT, MANUAL_PHASE, COURSE_REFERENCE, IMAGE_TEXT, MANUAL_FACT, REVIEWED_ANALYSIS }
enum class EvidenceAvailability { AVAILABLE, MISSING_FILE, INVALID_REFERENCE, UNKNOWN_CLASS_TIME, SUMMARY_ONLY, FORBIDDEN }
enum class PhaseLayer { OBSERVED_ACTIVITY, BOPPPS }
enum class PhaseReadMode { CURRENT_CANDIDATE, PUBLISHED_REPORT }
enum class BopppsStage(val label: String) {
    BRIDGE_IN("导入"), OBJECTIVE("目标"), PRE_ASSESSMENT("前测"),
    PARTICIPATORY_LEARNING("参与式学习"), POST_ASSESSMENT("后测"), SUMMARY("总结"),
}
enum class CourseAttentionState(val label: String) {
    ATTENTION("正式评价触发关注"), NORMAL("未触发关注阈值"),
    UNDETERMINED("正式分数无法判定"), UNPUBLISHED("尚无正式评价"),
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
    EVALUATION_DESIGN("教学设计分（人工评分）"),
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
    NO_INTERACTION("未标注互动的区间"),
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

/** v9：空白评价不能被解释成已确认没有问题。 */
enum class IssueDeclaration(val label: String) {
    UNASSESSED("尚未确认"), HAS_ISSUES("已记录主要问题"), NO_ISSUES_CONFIRMED("确认本次无主要问题"),
}

enum class IssuePresence(val label: String) {
    UNDETERMINED("尚不能确定"), PRESENT("原问题仍存在"), NOT_OBSERVED("本次未再观察到"),
}

enum class ImprovementExtent(val label: String) {
    UNDETERMINED("尚不能确定"), NONE("未改善"), PARTIAL("部分改善"), RESOLVED("本次已解决"),
}

enum class VerificationEvidenceKind(val label: String) {
    METRIC_PAIR("正式指标对比"), FOLLOW_UP_ISSUE("复评正式问题"),
    NO_ISSUES_DECLARATION("正式零问题声明"), FOLLOW_UP_OBSERVATION("核验人补充观察"),
}

enum class VerificationEvidenceRole(val label: String) {
    PRIMARY("主要依据"), AUXILIARY("辅助依据"),
}

enum class ReviewChronologyStatus(val label: String) {
    VALID("授课顺序有效"), SAME_TASK("不能关联同一次课堂"), REVERSED("复评早于原课堂"),
    OVERLAP("两次课堂节次重叠"), UNKNOWN("日期或同日节次不完整，无法判断授课顺序"),
}
