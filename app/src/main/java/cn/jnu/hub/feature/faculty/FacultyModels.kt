package cn.jnu.hub.feature.faculty

/**
 * 师资查询的数据模型。
 *
 * 数据来源是暨大教师主页系统的 webplus 通用查询接口
 * `POST https://faculty.jnu.edu.cn/_wp3services/generalQuery?queryObj=teacherHome`，
 * 免登录。字段名与站点前端（`template7/js/teaWebQuery.js`）保持一致：
 *
 * - 教师姓名在 **`title`** 字段里（不是 name）
 * - 教师主页地址在 **`cnUrl`** 字段里，形如
 *   `http://faculty.jnu.edu.cn/smkx/anj/list.htm`
 *   （虽然叫 list.htm，但它是该教师自己的综合信息页）
 * - 学院筛选用 `ownDepartment` = collegeId
 */

/** 搜索页一条结果 */
data class TeacherSummary(
    /** 教师姓名，对应接口的 title 字段 */
    val name: String,
    /** 职称，如「教授」「研究员（自然科学）」「副主任医师」，可能为空串 */
    val career: String = "",
    /** 所属学院 / 独立研究院，可能为空串 */
    val college: String = "",
    /** 邮箱，可能为空串 */
    val email: String = "",
    /** 办公电话，可能为空串 */
    val phone: String = "",
    /** 教师主页地址（详情页），可能为空串 */
    val homeUrl: String = "",
    /** 主页访问量，用于排序参考 */
    val visitCount: Int = 0,
    /** 主页头像地址（相对路径，需补 host），可能为空串 */
    val avatarPath: String = "",
) {
    /** 详情页稳定的唯一标识：主页地址；没有主页时用姓名+学院兜底 */
    val teacherId: String
        get() = homeUrl.ifBlank { "name:" + name + "|" + college }

    val hasEmail: Boolean get() = email.isNotBlank()
    val hasPhone: Boolean get() = phone.isNotBlank()

    /** 姓名首字，用于没有头像时画一个字母占位圆 */
    val initial: String get() = name.trim().take(1)
}

/** 一个学院筛选项 */
data class FacultyCollege(
    val collegeId: Int,
    val name: String,
    /** 该学院教师人数 */
    val count: Int = 0,
)

/** 一页搜索结果 */
data class TeacherPage(
    /** 命中总数 */
    val total: Int,
    /** 当前页数据 */
    val items: List<TeacherSummary>,
    /** 请求的页码（1 开始） */
    val pageIndex: Int,
    /** 每页条数 */
    val pageSize: Int,
) {
    /** 是否还有下一页 */
    val hasMore: Boolean get() = pageIndex * pageSize < total

    /** 是否是最后一页 */
    val isLastPage: Boolean get() = !hasMore
}

/**
 * 教师主页解析出的详情。
 *
 * 站点有两套模板：
 *  - 主模板：`<div class="post" id="jj"><h3 class="tit"><span class="title">个人简介</span></h3><div class="con">…</div></div>`
 *  - 老模板：`<li class="news clearfix"><span class="title">部门：</span><span class="txt">…</span></li>`
 * 这里两种都解析，统一成 [info]（键值对）+ [sections]（长文本分区）。
 */
data class TeacherDetail(
    /** 页面里最好的姓名（优先搜索接口带回的，其次页面内「姓名」字段） */
    val name: String,
    /** 职称 */
    val career: String = "",
    /** 学院 / 部门 */
    val department: String = "",
    /** 邮箱 */
    val email: String = "",
    /** 电话 */
    val phone: String = "",
    /** 头像绝对地址 */
    val avatarUrl: String = "",
    /** 结构化资料：部门 / 性别 / 学位 / 研究方向标签 / 办公地址 … */
    val info: List<InfoRow> = emptyList(),
    /** 长文本分区：个人简介 / 研究方向 / 主要论文 / 荣誉奖励 … */
    val sections: List<DetailSection> = emptyList(),
    /** 原始主页地址 */
    val sourceUrl: String = "",
) {
    val hasContact: Boolean get() = email.isNotBlank() || phone.isNotBlank()

    /** 除了部门/职称以外还值得展示的资料行 */
    val extraInfo: List<InfoRow>
        get() = info.filter { it.label !in CONTACT_AND_HEADER_LABELS }

    companion object {
        /** 已经在标题区单独展示的字段，资料列表里就不再重复 */
        private val CONTACT_AND_HEADER_LABELS = setOf(
            "姓名", "部门", "职称", "职务", "联系电话", "电子邮箱", "邮箱", "电话",
        )
    }
}

/** 一条结构化资料 */
data class InfoRow(val label: String, val value: String)

/** 一个长文本分区 */
data class DetailSection(
    val title: String,
    val content: String,
) {
    val isEmpty: Boolean get() = content.isBlank()
}
