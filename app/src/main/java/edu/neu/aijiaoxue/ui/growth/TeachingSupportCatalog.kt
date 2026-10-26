package edu.neu.aijiaoxue.ui.growth

import edu.neu.aijiaoxue.data.model.EvalDimension

data class InteractionStrategy(
    val key: String,
    val title: String,
    val content: String,
    val rationale: String,
    val resourceKeys: List<String>,
)

data class TeachingResource(
    val key: String,
    val title: String,
    val sourceName: String,
    val sourceUrl: String,
    val summary: String,
    val steps: String,
    val dimension: EvalDimension,
)

/**
 * US39/40：本地规则与离线教学参考，不调用模型，不判断输入问题是否已在课堂中成立。
 * 原始页面于 2026-10-26 核验；中文摘要及操作步骤为项目自行整理，原链接用于查阅出处。
 * 候选的选择、修改和进入计划由调用方处理，本目录不修改评价、计划或课堂指标。
 */
object TeachingSupportCatalog {
    val resources: List<TeachingResource> = listOf(
        TeachingResource(
            key = "cornell-think-pair-share",
            title = "先思考、再结对、后分享",
            sourceName = "Cornell University · Center for Teaching Innovation",
            sourceUrl = "https://teaching.cornell.edu/teaching-resources/active-collaborative-learning/active-learning/getting-started-active-learning",
            summary = "教学参考：让学生先形成个人回答，再交换理由，最后向全班分享。适合增加发言准备与交流机会。",
            steps = "围绕课程概念设置一个问题；留出独立思考或书写时间；两人交换答案；邀请若干组分享，由教师回应分歧。时间与题目由教师调整。",
            dimension = EvalDimension.INTERACTION,
        ),
        TeachingResource(
            key = "berkeley-peer-instruction",
            title = "独立作答、同伴解释与再次作答",
            sourceName = "University of California, Berkeley · Center for Teaching & Learning",
            sourceUrl = "https://teaching.berkeley.edu/teaching-strategies/teaching-your-course/active-learning",
            summary = "教学参考：通过两次个人作答与中间的同伴讨论，观察学生的解释及答案变化，决定是否需要进一步讲解。",
            steps = "给出概念题并收集个人答案；请同伴比较理由而非只报选项；再次独立作答；教师结合回答与解释进行反馈。可用纸卡记录。",
            dimension = EvalDimension.INTERACTION,
        ),
        TeachingResource(
            key = "duke-minute-paper",
            title = "一分钟学习反馈与疑问卡",
            sourceName = "Duke University · Center for Teaching and Learning",
            sourceUrl = "https://ctl.duke.edu/resources/art-and-science-of-teaching/active-learning-techniques-classroom/",
            summary = "教学参考：用短书面回答了解学生记住的重点与仍不清楚之处，给不愿当众发言的学生提供反馈机会。",
            steps = "在自然停顿处请学生写下一个收获或疑问；收集并归类；下次课堂回应共同困难。无需公开姓名，也不能仅凭交卡数量判定理解程度。",
            dimension = EvalDimension.ENGAGEMENT,
        ),
        TeachingResource(
            key = "uq-predict-observe-explain",
            title = "预测、观察与解释",
            sourceName = "The University of Queensland · Institute for Teaching and Learning Innovation",
            sourceUrl = "https://itali.uq.edu.au/node/6553",
            summary = "教学参考：围绕演示或案例数据先作预测，再观察结果并解释差异，让学生参与推理与实践。",
            steps = "选择适合课程的演示或案例；个人记录预测及理由；观察并记录结果；说明预测与观察的差别，再交流解释。教师确认材料适用性。",
            dimension = EvalDimension.ENGAGEMENT,
        ),
    )

    /** 空白、笼统评价、否定表述或证据不足时不制造“诊断”；未知维度仅接受明确的问题文本。 */
    fun strategies(dimension: EvalDimension?, problem: String): List<InteractionStrategy> {
        if (dimension != null && dimension != EvalDimension.INTERACTION && dimension != EvalDimension.ENGAGEMENT) {
            return emptyList()
        }
        val description = problem.trim()
        if (description.isEmpty()) return emptyList()
        val searchable = description.replace(Regex("[\\s\\u3000]+"), "")
        if (uncertainInput.containsMatchIn(searchable)) return emptyList()
        val clauses = description.split(Regex("[，,。；;！？!?\\n]+"))
            .map { it.replace(Regex("[\\s\\u3000]+"), "") }
            .filter { it.isNotEmpty() && !negatedProblem.containsMatchIn(it) && !resolvedProblem.containsMatchIn(it) }
        val lowInteraction = clauses.any { interactionProblem.containsMatchIn(it) }
        val shallowReasoning = clauses.any { reasoningProblem.containsMatchIn(it) }
        val lowEngagement = clauses.any { engagementProblem.containsMatchIn(it) }
        val missingPractice = clauses.any { practiceProblem.containsMatchIn(it) }
        val inputLabel = description.replace(Regex("\\s+"), " ").let {
            if (it.length > 200) it.take(200) + "…" else it
        }

        return buildList {
            if (dimension == null || dimension == EvalDimension.INTERACTION) {
                if (lowInteraction) add(candidate(
                    "think-pair-share", "为发言准备留出思考与结对交流",
                    "围绕本课概念设置一个问题。学生先独立写下回答，再两人交换理由，最后邀请不同小组分享。" +
                        "观察记录：同一观察时段内发言的不同学生人数、到场人数及分享理由；不只统计教师提问次数。",
                    inputLabel, "问题描述涉及互动机会、回应或讨论不足，可先尝试扩大回答准备与交流机会。",
                    "cornell-think-pair-share",
                ))
                if (lowInteraction || shallowReasoning) add(candidate(
                    "peer-instruction", "用同伴解释和再次作答检查互动质量",
                    "选择一题与本课目标相关的概念题，先独立作答，再由同伴互相解释理由，最后重新作答并由教师反馈。" +
                        "观察记录：两轮作答人数、答案分布及理由样例；答案变化本身不代表已经掌握。",
                    inputLabel, "问题描述涉及回应不足或理由不充分，可用两次作答之间的解释交流补充观察。",
                    "berkeley-peer-instruction",
                ))
            }
            if (dimension == null || dimension == EvalDimension.ENGAGEMENT) {
                if (lowEngagement) add(candidate(
                    "minute-paper", "用短书面反馈扩大参与渠道",
                    "在本课一个自然停顿处，请每位学生写下一个收获或未理解的问题；教师归类后及时回应。" +
                        "观察记录：提交人数、到场人数和疑问类别，并与相同观察口径的后续课堂对照；交卡不等于理解。",
                    inputLabel, "问题描述涉及参与不足、下降或注意力分散，可增加书面参与机会并收集真实困难。",
                    "duke-minute-paper",
                ))
                if (lowEngagement || missingPractice) add(candidate(
                    "predict-observe-explain", "加入可观察的预测与实践任务",
                    "选取本课可演示的现象或案例数据，让学生先写预测、再观察并记录，最后解释差异。" +
                        "观察记录：完成预测、观察和解释的学生人数，以及到场人数和解释样例；教师据此调整下一次任务。",
                    inputLabel, "问题描述涉及参与或实践机会不足，可用明确的活动产出观察参与过程。",
                    "uq-predict-observe-explain",
                ))
            }
        }
    }

    private fun candidate(
        key: String,
        title: String,
        content: String,
        problem: String,
        reason: String,
        resourceKey: String,
    ) = InteractionStrategy(
        key = key,
        title = title,
        content = content,
        rationale = "本地规则依据输入问题“$problem”匹配。$reason 教学参考，由教师结合课堂证据选择、修改或放弃；不是模型诊断或成效结论。",
        resourceKeys = listOf(resourceKey),
    )

    private val uncertainInput = Regex("证据不足|数据不足|暂无数据|无数据|无有效数据|信息不足|资料缺失|未采集|未记录|无法判断|无法确认|待观察|待核验|待确认|尚不明确|不确定|疑似|可能|是否")
    private val negatedProblem = Regex(
        "(?:未发现|没有发现|没有出现|未见|不存在|并非|不是|没有|无)(?:明显的?|任何|严重的?)?(?:学生的?|课堂的?)?" +
            "(?:互动不足|参与不足|参与度下降|参与率下降|参与下降|发言少|讨论少|学生走神|走神|分心)",
    )
    private val resolvedProblem = Regex("(?:不足|下降|偏低|不均|走神|分心).{0,6}(?:已解决|已改善|已消除|不再出现)")
    private val interactionProblem = Regex(
        "(?:互动|提问|回答|回应|发言|讨论|交流)(?:机会|次数|人数|覆盖面|频率|环节)?" +
            "(?:明显|持续|比较|较|偏|过|仍)?(?:不足|减少|稀少|偏少|较少|少|缺失|单一|不充分|不活跃|不积极|弱|低|下降|不均)" +
            "|(?:缺少|缺乏|缺失|没有|未开展|未安排|很少)(?:有效|足够|学生|课堂)?(?:互动|提问|讨论|交流|回答|回应|发言)" +
            "|(?:仅|只有)?少数(?:学生|同学)(?:回答|回应|发言)" +
            "|(?:学生|多数学生|大部分学生)(?:很少|不|未)(?:回答|回应|发言)",
    )
    private val reasoningProblem = Regex("(?:回答|讨论|解释)(?:缺少|缺乏|没有)理由|只(?:报|说|回答)答案|缺少追问|追问不足|浅层互动")
    private val engagementProblem = Regex(
        "(?:参与度|参与率|参与|投入|专注度|专注|注意力)(?:人数|机会|覆盖面)?" +
            "(?:明显|持续|比较|较|偏|越来越)?(?:不足|下降|偏低|较低|低|减少|不均|不集中|分散)" +
            "|(?:缺少|缺乏)(?:学生)?参与|学生(?:走神|分心|低头|被动听讲)",
    )
    private val practiceProblem = Regex("(?:实践|练习|操作)(?:机会|活动|环节|任务)?(?:明显|较|偏)?(?:不足|缺失|太少|较少|少)|(?:缺少|缺乏|没有)(?:实践|练习|操作)")
}
