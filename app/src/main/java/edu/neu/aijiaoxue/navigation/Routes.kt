package edu.neu.aijiaoxue.navigation

import edu.neu.aijiaoxue.data.model.Role


/**
 * 全部路由集中定义。
 *
 * 模块之间跳转只传 ID：
 * taskId、courseId、analysisId 等。
 *
 * 不传对象。
 */
object Routes {


    const val LOGIN = "login"

    const val PROFILE = "profile"



    // =========================
    // 角色首页
    // =========================

    const val HOME_ADMIN =
        "home/admin"


    const val HOME_SUPERVISOR =
        "home/supervisor"


    const val HOME_TEACHER =
        "home/teacher"




    // =========================
    // ui/course
    // =========================

    const val COURSE_SEARCH =
        "course/search"



    const val COURSE_DETAIL =
        "course/{courseId}"



    fun courseDetail(
        courseId: Long
    ) =
        "course/$courseId"



    const val TASK_CREATE =
        "course/{courseId}/task/new"



    fun taskCreate(
        courseId: Long
    ) =
        "course/$courseId/task/new"



    const val MY_TASKS =
        "task/mine"




    // =========================
    // ui/arrange
    // =========================

    const val ARRANGEMENT =
        "arrange"



    const val TASK_PROGRESS =
        "arrange/progress"




    // =========================
    // ui/capture
    // ui/engagement
    // =========================

    const val CAPTURE =
        "capture/{taskId}"



    fun capture(
        taskId: Long
    ) =
        "capture/$taskId"





    // =========================
    // Sprint2 AI分析模块
    // =========================


    /**
     * AI课堂分析页面
     *
     * 参数：
     * taskId
     */
    const val ANALYSIS =
        "analysis/{taskId}"



    fun analysis(
        taskId: Long
    ) =
        "analysis/$taskId"





    /**
     * Sprint2 US27
     *
     * AI结果人工审核页面
     *
     * 参数：
     * analysisId
     */
    const val AI_REVIEW =
        "ai_review/{analysisId}"



    fun aiReview(
        analysisId: Long
    ) =
        "ai_review/$analysisId"






    // =========================
    // ui/evaluation
    // =========================


    const val EVALUATION =
        "evaluation/{taskId}"



    fun evaluation(
        taskId: Long
    ) =
        "evaluation/$taskId"



    const val HISTORY =
        "history"



    const val HISTORY_DETAIL =
        "history/{taskId}"



    fun historyDetail(
        taskId: Long
    ) =
        "history/$taskId"






    // =========================
    // ui/feedback
    // =========================


    const val FEEDBACK_LIST =
        "feedback"



    const val FEEDBACK_DETAIL =
        "feedback/{taskId}"



    fun feedbackDetail(
        taskId: Long
    ) =
        "feedback/$taskId"







    // =========================
    // ui/improve
    // =========================


    const val IMPROVE_FOCUS =
        "improve/{taskId}/focus"



    fun improveFocus(
        taskId: Long
    ) =
        "improve/$taskId/focus"




    const val IMPROVE_PLAN =
        "improve/{taskId}/plan"



    fun improvePlan(
        taskId: Long
    ) =
        "improve/$taskId/plan"




    const val IMPROVE_TODO =
        "improve/todo"






    // =========================
    // 参数名
    // =========================


    const val ARG_TASK_ID =
        "taskId"



    const val ARG_COURSE_ID =
        "courseId"



    const val ARG_ANALYSIS_ID =
        "analysisId"







    /**
     * F0：
     * 登录后按角色进入对应首页。
     *
     * 越权访问时也回到这里。
     */
    fun homeOf(
        role: Role
    ) =
        when(role) {


            Role.ADMIN ->
                HOME_ADMIN



            Role.SUPERVISOR ->
                HOME_SUPERVISOR



            Role.TEACHER ->
                HOME_TEACHER


        }


}