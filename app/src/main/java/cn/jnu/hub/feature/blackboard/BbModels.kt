package cn.jnu.hub.feature.blackboard

/**
 * Blackboard (Learn 9.1) 数据模型。
 *
 * 全部来自 HTML 解析（本 BB 无 REST API），字段按「页面上真实能看到的东西」设计，
 * 不做过度抽象：解析器拿不到就留空字符串，由 UI 决定怎么降级展示。
 */

/** 课程 / 机构列表项（来自 tabAction 的 launcher 链接） */
data class BbCourse(
    /** 形如 `_659_1`，可直接拼进 course_id / id 参数 */
    val id: String,
    val name: String,
    /** true = Organization（机构页），false = Course（真实课程） */
    val isOrganization: Boolean,
) {
    /** launcher 入口地址，WebView 兜底时用 */
    val launcherUrl: String
        get() = "$BASE/webapps/blackboard/execute/launcher?type=Course&id=$id&url="

    companion object {
        const val BASE = "https://blackboard.jnu.edu.cn"
    }
}

/** 课程主页信息 */
data class BbCourseHome(
    val courseId: String,
    val courseName: String,
    /** 课程内容区首个可进入的 listContent 地址中的 content_id，找不到为空 */
    val contentId: String = "",
    /** 课程菜单条目（模块内容 / 通知 / 讨论 / 回放等） */
    val menu: List<BbMenuItem> = emptyList(),
)

/** 课程菜单的一项 */
data class BbMenuItem(
    val title: String,
    /** 站内路径（以 / 开头），或完整 http(s) 地址 */
    val url: String,
    /** 外部地址（Panopto 等）需要 WebView 打开 */
    val external: Boolean = false,
)

/** 课程内容区（listContent）的一行 */
data class BbContentItem(
    val contentId: String,
    val title: String,
    /** 图标类别：folder / file / document / link / test / assignment / blank */
    val kind: BbContentKind,
    /** 点进去的地址：文件夹 = listContent.jsp；文件 = content/file；无 = "" */
    val linkUrl: String = "",
    /** 关联作业（uploadAssignment）的 content_id，没有则空 */
    val assignmentContentId: String = "",
    /**
     * 关联测评（launchAssessment）的 content_id —— 也就是 BB 的「作业 / 测验」类条目。
     *
     * 这类条目在课程内容区里的图标是 test_on.gif，链接形如
     * `/webapps/blackboard/content/launchAssessment.jsp?course_id=..&content_id=_425_1&mode=view`。
     * 它们往往**不是** uploadAssignment，却同样是「要交的作业」（如 GT CA1）。
     */
    val assessmentContentId: String = "",
    val attachments: List<BbAttachment> = emptyList(),
    /** 正文摘要（去掉附件后的说明文字，可能为空） */
    val summary: String = "",
) {
    /** 是否是一件「要交的东西」（作业 或 测评） */
    val isAssignmentLike: Boolean
        get() = assignmentContentId.isNotBlank() ||
            assessmentContentId.isNotBlank() ||
            kind == BbContentKind.ASSIGNMENT

    /** 评价类条目的 content_id（作业优先，其次测评） */
    val evalContentId: String
        get() = assignmentContentId.ifBlank { assessmentContentId }
}

enum class BbContentKind { FOLDER, FILE, DOCUMENT, LINK, TEST, ASSIGNMENT, OTHER }

/** 一个可下载的文件 */
data class BbAttachment(
    val name: String,
    /** 站内路径或完整地址 */
    val url: String,
    /** 文件大小文案，如 "1.349 MB" */
    val size: String = "",
) {
    /** 绝对地址，供 WebView / 下载用 */
    val absoluteUrl: String
        get() = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> BbCourse.BASE + url
            else -> BbCourse.BASE + "/" + url
        }
}

/** 一条通知 */
data class BbAnnouncement(
    /** 通知 ID，形如 `_2419_1` */
    val id: String,
    val title: String,
    /** 发布时间原文，如 "Wednesday, September 30, 2026 1:20:57 AM CST" */
    val postedOn: String = "",
    val postedBy: String = "",
    val postedTo: String = "",
    /** 正文纯文本（用于列表预览） */
    val preview: String = "",
    /** 正文 HTML（通知详情渲染用） */
    val bodyHtml: String = "",
    /** 正文内嵌图片 / 附件 */
    val attachments: List<BbAttachment> = emptyList(),
    /** 正文里的外链 */
    val links: List<BbLink> = emptyList(),
    val courseId: String = "",
)

/** 正文里的链接 */
data class BbLink(val text: String, val url: String) {
    val absoluteUrl: String
        get() = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("/") -> BbCourse.BASE + url
            else -> BbCourse.BASE + "/" + url
        }
}

/** 作业 / 测评的来源 */
enum class BbAssignmentSource {
    /** 课程内容区（Content）里的 uploadAssignment 条目 */
    CONTENT,

    /** 课程内容区里的 launchAssessment 条目（BB 的作业/测验） */
    ASSESSMENT,

    /** 成绩中心（myGrades）里「未出分」的条目 —— 只补标题与截止，无说明正文 */
    GRADEBOOK,
}

/**
 * 一次作业。
 *
 * 数据可能来自三处（见 [source]）：内容区的 uploadAssignment、内容区的
 * launchAssessment，以及成绩中心里尚未出分的条目。三者合并去重后才交给 UI。
 */
data class BbAssignment(
    /** 课程内容项 ID（uploadAssignment / launchAssessment 的 content_id） */
    val contentId: String,
    val title: String,
    /** 截止时间原文，如 "October 14, 2026 1:00 PM" */
    val dueDate: String = "",
    /** 来源 */
    val source: BbAssignmentSource = BbAssignmentSource.CONTENT,
    /** 可直接打开的原始地址（测评 / 成绩中心条目用） */
    val openUrl: String = "",
    /** 作业说明 HTML */
    val instructionsHtml: String = "",
    /** 作业说明纯文本 */
    val instructionsText: String = "",
    /** 作业附件（题目 / 模板） */
    val files: List<BbAttachment> = emptyList(),
    /** 已提交的尝试 */
    val attempts: List<BbSubmission> = emptyList(),
    /** 总分上限，如 "/100" 里的 100；未知为 null */
    val pointsPossible: String = "",
    /** 最近一次得分，未评为空串 */
    val grade: String = "",
    val courseId: String = "",
) {
    /** 还没有分数（作业 Tab 用它决定显示「未评分」还是分数） */
    val isGraded: Boolean get() = grade.isNotBlank()
}

/** 一次提交 */
data class BbSubmission(
    val label: String = "",
    val date: String = "",
    val grade: String = "",
    val files: List<BbAttachment> = emptyList(),
)

/** 一条成绩 */
data class BbGrade(
    val id: String,
    val item: String,
    /** 分类，如 Survey / Assignment */
    val category: String = "",
    /** 最近活动时间 */
    val lastActivity: String = "",
    /** Graded / Upcoming / Submitted … */
    val activityType: String = "",
    /** 成绩文字；未出分为空串 */
    val grade: String = "",
    /** Completed / Needs Grading … */
    val status: String = "",
    /** 是否是合计行（Total / Weighted Total） */
    val isCalculated: Boolean = false,
    /** 截止时间（行里 duedate 属性换算，或文本里的 "Due: xxx"）；未知为空 */
    val dueDate: String = "",
    /** 原始 onClick 里的站内地址（v2 结构里有），用来反查对应的作业/测评 */
    val detailUrl: String = "",
) {
    /**
     * 是否是「成绩中心里的作业类条目」—— 用于把还没出分的作业补进「作业」Tab。
     *
     * 判据：不是合计行、没有分数，且状态显示未交/待提交/未评分，
     * 或者行上带了截止时间（只有作业类条目才有截止）。
     */
    val isPendingAssignment: Boolean
        get() = !isCalculated && !hasGrade && (
            status.contains("Not Submitted", ignoreCase = true) ||
                status.contains("Needs Grading", ignoreCase = true) ||
                status.contains("未交", ignoreCase = true) ||
                activityType.contains("Upcoming", ignoreCase = true) ||
                activityType.contains("Submitted", ignoreCase = true) ||
                dueDate.isNotBlank()
            )

    val hasGrade: Boolean get() = grade.isNotBlank() && grade != "-"
}

/** 成绩页整体 */
data class BbGradeSheet(
    val courseId: String,
    val courseName: String = "",
    val items: List<BbGrade> = emptyList(),
) {
    /** 合计类行（Total / Weighted Total） */
    val totals: List<BbGrade> get() = items.filter { it.isCalculated }
    /** 明细项 */
    val details: List<BbGrade> get() = items.filter { !it.isCalculated }
    /** 已出分的明细项数 */
    val gradedCount: Int get() = details.count { it.hasGrade }
}

/** 课堂回放（Panopto） */
data class BbRecording(
    val title: String,
    /** 播放页地址 */
    val url: String,
    /** 出处：课程菜单 / 课程内容里的链接 */
    val source: String = "",
)

/**
 * 「作业」Tab 的聚合结果。
 *
 * [note] 用来区分「真的一件都没有」和「只找到了部分」：
 * 成绩中心里明明有待交条目、但内容区拿不到详情时，就要如实告诉用户，
 * 而不是一句「暂无作业」把人劝退。
 */
data class BbAssignmentSheet(
    val courseId: String,
    val items: List<BbAssignment> = emptyList(),
    /** 内容区里登记了但没抓到详情的条目数 */
    val partialCount: Int = 0,
    /** 只在成绩中心可见（内容区找不到对应项）的条目数 */
    val gradebookOnlyCount: Int = 0,
    /** 更具体的说明（例如课程内容区整个取不到）；空串表示没有 */
    val detailNote: String = "",
) {
    val isEmpty: Boolean get() = items.isEmpty()

    /** 给「暂无作业」用的补充说明；空串表示确实一件都没有 */
    val note: String
        get() = when {
            detailNote.isNotBlank() -> detailNote
            partialCount > 0 -> "有 " + partialCount + " 项未能加载详情，可能没有访问权限。"
            gradebookOnlyCount > 0 -> "其中 " + gradebookOnlyCount + " 项只在成绩中心可见，未能取得详细说明。"
            else -> ""
        }
}

/** 课程详情里「回放」Tab 的一次聚合结果 */
data class BbRecordingSheet(
    val courseId: String,
    /** 站内 Panopto 工具入口，找不到为空 */
    val panoptoEntry: String = "",
    val recordings: List<BbRecording> = emptyList(),
)
