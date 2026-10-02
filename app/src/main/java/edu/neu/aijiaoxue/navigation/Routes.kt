package edu.neu.aijiaoxue.navigation

/**
 * 全部路由集中定义。模块之间跳转只传 ID（taskId、courseId 等），不传对象。
 * 新增路由在此追加，并在群里说一声。带参数的路由用 xxx(id) 生成实际地址。
 */
object Routes {
    const val LOGIN = "login"
    const val PROFILE = "profile"

    // 角色首页
    const val HOME_ADMIN = "home/admin"
    const val HOME_SUPERVISOR = "home/supervisor"
    const val HOME_TEACHER = "home/teacher"

    // ui/course（王博田）
    const val COURSE_SEARCH = "course/search"
    const val COURSE_DETAIL = "course/{courseId}"
    fun courseDetail(courseId: Long) = "course/$courseId"
    const val TASK_CREATE = "course/{courseId}/task/new"
    fun taskCreate(courseId: Long) = "course/$courseId/task/new"
    const val MY_TASKS = "task/mine"

    // ui/arrange（王博田）
    const val ARRANGEMENT = "arrange"
    const val TASK_PROGRESS = "arrange/progress"

    // ui/capture、ui/engagement（李奕萱、孙浩然）
    const val CAPTURE = "capture/{taskId}"
    fun capture(taskId: Long) = "capture/$taskId"

    // ui/evaluation（于卓君）
    const val EVALUATION = "evaluation/{taskId}"
    fun evaluation(taskId: Long) = "evaluation/$taskId"
    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{taskId}"
    fun historyDetail(taskId: Long) = "history/$taskId"

    // ui/feedback（王博田）
    const val FEEDBACK_LIST = "feedback"
    const val FEEDBACK_DETAIL = "feedback/{taskId}"
    fun feedbackDetail(taskId: Long) = "feedback/$taskId"

    // ui/improve（于卓君）
    const val IMPROVE_FOCUS = "improve/{taskId}/focus"
    fun improveFocus(taskId: Long) = "improve/$taskId/focus"
    const val IMPROVE_PLAN = "improve/{taskId}/plan"
    fun improvePlan(taskId: Long) = "improve/$taskId/plan"
    const val IMPROVE_TODO = "improve/todo"

    const val ARG_TASK_ID = "taskId"
    const val ARG_COURSE_ID = "courseId"
}
