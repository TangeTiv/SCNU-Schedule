package com.xingheyuzhuan.shiguangschedule.data.network.selection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════════════════════
// SCNU 自主选课模块 — 数据模型
//
// 严格 1:1 对应 scnu_course_selector.py 中的 dict 归一化结果与 hidden 上下文。
//
// ## 设计约束（重要）
//
// 1. **全部字段使用 String**：教务后端对同一语义字段可能返回空串、纯数字、
//    文字评语（如"优秀"），用 String 承载可避免解析崩溃。
// 2. **不是 Room 实体**：本模块是临时沙盒，退出即清，不落库。
// 3. **DTO 只做字段映射**，不做业务判断（"是否已满"等派生逻辑放在 Mapper 或 UI 层）。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 选课页 hidden 上下文。
 *
 * 对应 Python 的 `self.ctx` —— 从选课首页 HTML 中抓取的全部 `<input type=hidden>`。
 *
 * **为什么必须动态抓取而不能硬编码**：脚本 `:81-83` 注释指出，缺失这些规则参数时
 * 服务器**不报错**，而是静默忽略专业/年级筛选，返回全校课程。因此这些字段
 * 是"只看到本专业课程"的命根子。
 *
 * 用 [raw] 保存全量键值，用 [get] 做安全取值 —— 避免为 38+ 个字段逐个声明属性。
 */
data class SelectionContext(val raw: Map<String, String>) {

    /** 安全取值：缺失或空串时返回 [fallback] */
    fun get(key: String, fallback: String = ""): String =
        raw[key]?.takeIf { it.isNotBlank() } ?: fallback

    /** 按优先级取第一个非空值，对应 Python `f(*keys, default)` 的多键回退语义 */
    fun first(vararg keys: String): String {
        for (k in keys) {
            val v = raw[k]
            if (v != null && v.isNotBlank()) return v
        }
        return ""
    }

    /** 学年学期名（页面 JS 渲染，需从 HTML 单独正则提取，见 [_xnmc]） */
    val yearName: String get() = first("_xnmc", "xkxnmc", "xkxnm")

    /** 学期名 */
    val termName: String get() = first("_xqmc", "xkxqmc", "xkxqm")

    /** 选课轮次序号（纯数字，从"第5轮"文案中提取） */
    val roundName: String get() = first("_xklcmc", "xklcmc")

    /** 是否在选课时间内（对应 iskxk == "1"） */
    val isInSelectionWindow: Boolean get() = get("iskxk") == "1"

    /** 服务器时间 */
    val serverTime: String get() = get("currentsj")

    /** 选课开始时间（该页通常为空，轮次时间由页面 JS 渲染） */
    val startTime: String get() = get("xkkssj")

    /** 选课结束时间 */
    val endTime: String get() = get("xkjssj")

    /** 最高可选学分 */
    val maxCredits: String get() = get("xkzgxf")

    /** 已选学分 */
    val selectedCredits: String get() = get("zxfs")

    /** 已选门次 */
    val selectedCount: String get() = get("zkcs")

    /**
     * 教务侧的学年学期编码（用于课表查询等接口）。
     *
     * 注意课程列表接口用的是 `xkxnm/xkxqm`，课表接口却用 `xnm/xqm`，
     * 取值来源相同但参数名不同，故分两个属性暴露。
     */
    val academicYear: String get() = get("xkxnm")

    val academicTerm: String get() = get("xkxqm")
}

/**
 * 选课类别（页面 Tab）。
 *
 * 来源：选课首页 HTML 中的 `queryCourse(this, 'kklxdm', 'xkkz_id', 'njdm_id', 'zyh_id')`
 * JS 调用，用正则提取。
 *
 * @param typeCode 课程类别代号（`kklxdm`）：01=主修、10=通识选修、41=第二类
 * @param controlId 选课控制 ID（`xkkz_id`）
 * @param gradeId 年级代码（`njdm_id`）
 * @param majorId 专业号（`zyh_id`）
 * @param displayName 页面上的 Tab 文案（从 `id="tab_kklx_..."` 元素提取）
 */
data class CourseCategory(
    @SerialName("kklxdm") val typeCode: String,
    @SerialName("xkkz_id") val controlId: String,
    @SerialName("njdm_id") val gradeId: String,
    @SerialName("zyh_id") val majorId: String,
    val displayName: String
)

/**
 * 可选课程（教学班）概要。
 *
 * 来源：`/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html` 返回的 `tmpList[]`，
 * 经 `_norm_course()` 归一化。
 *
 * ## 关于 [rankInBatch]（`kcrow`）
 *
 * 教务采用"客户端窗口"分页：无论请求哪一批，服务器都可能推送超出窗口的行，
 * 客户端必须用 `kcrow`（批次内行号）切出真正属于本批的行。详见
 * [ScnuCourseSelector.listCoursesBatch]。
 */
@Serializable
data class SelectableCourse(
    /** 课程内部 ID，选课与查详情必需 */
    @SerialName("kch_id") val courseId: String = "",
    /** 课程号（人类可读，如 "(2024)123456"） */
    @SerialName("kch") val courseCode: String = "",
    @SerialName("kcmc") val courseName: String = "",
    @SerialName("xf") val credits: String = "",
    /** 教学班 ID，选课提交用 */
    @SerialName("jxb_id") val classId: String = "",
    @SerialName("jxbmc") val className: String = "",
    /** 批次内行号，用于窗口切分 */
    @SerialName("kcrow") val rankInBatch: String = "",
    @SerialName("kklxdm") val typeCode: String = "",
    /** 重修标记 "1"=是 */
    @SerialName("cxbj") val isRetake: String = "0",
    /** 辅修标记 "1"=是 */
    @SerialName("fxbj") val isMinor: String = "0",
    /** 有先行课标记 */
    @SerialName("xxkbj") val hasPrerequisite: String = "0",
    /** 是否推荐课 */
    @SerialName("sftj") val isRecommended: String = "0",
    /**
     * 教学班子数（`jxbzls`）。
     *
     * **> 1 表示该教学班由多个子课程组成**（理论/实验/上机…），
     * 此时不能直接提交单个 `do_jxb_id`，必须先让用户勾选子课程，
     * 再把多个 `do_jxb_id` 用逗号拼成 `jxb_ids` 一起提交。
     */
    @SerialName("jxbzls") val subCourseCount: String = "1",
    /** 已选人数 */
    @SerialName("yxzrs") val enrolledCount: String = "",
    /** 任务总学时 */
    @SerialName("rwzxs") val totalHours: String = "",
    @SerialName("kzmc") val courseNature: String = "",
    /** 教学班容量（`jxbrl`）。列表接口不返回，仅详情接口有；空串表示**容量未知** */
    @SerialName("jxbrl") val capacity: String = "",
    /**
     * 教学班已选人数（`jxbrs`）。
     *
     * 详情接口用 `jxbrs` 表示已选人数，而列表接口用 `yxzrs`；两者语义相同、
     * 字段名不同，故独立声明。由 ViewModel 映射时择一填入 [enrolledCount]。
     */
    @SerialName("jxbrs") val classEnrolledCount: String = ""
) {
    /** 去重键：优先 `jxb_id`，缺失时退化为 `kch_id`（对应 Python `key = jxb_id or kch_id`） */
    val dedupeKey: String get() = classId.ifBlank { courseId }

    /** 是否含子课程，需走"勾选子课程"流程 */
    val hasSubCourses: Boolean get() = (subCourseCount.toIntOrNull() ?: 1) > 1

    /** 是否为重修/辅修课程 */
    val isRetakeOrMinor: Boolean get() = isRetake == "1" || isMinor == "1"

    /**
     * 是否**已满**。
     *
     * ## 只在容量已知时才判定（重要）
     *
     * 课程列表接口不返回容量 `jxbrl`（脚本 `_norm_course` 也只取 `yxzrs`），
     * 因此列表层**没有依据**判断是否已满。早期实现用"已选人数 ≥ 60"这种
     * 凭空设定的阈值兜底，会把有余量的课程误标为已满 —— 已废弃。
     *
     * 现在的语义是：**容量未知 → 一律返回 false（不标注、不禁选）**。
     * 用户仍可点进去看教学班详情，那里有精确的 `jxbrs`/`jxbrl` 可以判定。
     */
    val isFull: Boolean
        get() {
            val cap = capacity.toDoubleOrNull() ?: return false
            if (cap <= 0) return false
            // 详情接口已选人数优先 jxbrs，回退 yxzrs（与脚本 `:509-511` 一致）
            val selected = (classEnrolledCount.ifBlank { enrolledCount }).toDoubleOrNull()
                ?: return false
            return selected >= cap
        }

    /** 是否具备容量信息（决定 [isFull] 与 [occupancyText] 是否可信） */
    val hasCapacityInfo: Boolean get() = (capacity.toDoubleOrNull() ?: 0.0) > 0

    /** 已选/容量展示串，如 "45/60"；容量未知时只显示已选人数 */
    val occupancyText: String
        get() {
            val selected = classEnrolledCount.ifBlank { enrolledCount }
            return when {
                selected.isBlank() && capacity.isBlank() -> ""
                capacity.isBlank() -> "$selected 人"
                else -> "$selected/$capacity"
            }
        }
}

/**
 * 教学班详情。
 *
 * 来源：`/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html`。
 *
 * **关键字段 [doJxbId]** —— 选课提交真正需要的 ID，与列表接口返回的 `jxb_id`
 * **不是同一个值**，缺它无法选课。
 */
@Serializable
data class CourseClass(
    @SerialName("kch_id") val courseId: String = "",
    @SerialName("kch") val courseCode: String = "",
    @SerialName("kcmc") val courseName: String = "",
    @SerialName("jxb_id") val classId: String = "",
    /** 选课必需的提交 ID */
    @SerialName("do_jxb_id") val doJxbId: String = "",
    @SerialName("jxbmc") val className: String = "",
    @SerialName("xf") val credits: String = "",
    /** 教师原始串，格式 `工号/姓名/职称`，需经 [parseTeacherNames] 解析 */
    @SerialName("jsxx") val teacherRaw: String = "",
    /** 上课时间（教务原文，如 "一 第1-2节 1-16周"） */
    @SerialName("sksj") val classTime: String = "",
    /** 教学地点 */
    @SerialName("jxdd") val classLocation: String = "",
    /** 教学班容量 */
    @SerialName("jxbrl") val capacity: String = "",
    /**
     * 教学班已选人数（`jxbrs`）。
     *
     * 详情接口用 `jxbrs`，列表接口用 `yxzrs`；两者语义相同、字段名不同。
     * 已满判定优先取本字段，缺失时回退 [enrolledCount]（对应脚本 `:509-511`）。
     */
    @SerialName("jxbrs") val classEnrolledCount: String = "",
    /** 已选人数 */
    @SerialName("yxzrs") val enrolledCount: String = "",
    /** 子课程数 */
    @SerialName("jxbzls") val subCourseCount: String = "",
    @SerialName("xqumc") val campus: String = "",
    @SerialName("yqmc") val zone: String = "",
    @SerialName("kkxymc") val college: String = "",
    /** 课程性质（必修/选修） */
    @SerialName("kcxzmc") val courseNature: String = "",
    @SerialName("kclbmc") val courseCategory: String = "",
    @SerialName("jxms") val teachingMode: String = "",
    /** 必修标记 */
    @SerialName("bxbj") val isCompulsory: String = "0",
    @SerialName("fxbj") val isMinor: String = "0",
    @SerialName("cxbj") val isRetake: String = "0",
    @SerialName("xkbz") val remark: String = ""
) {
    /** 教师显示名（已解析），见 [parseTeacherNames] */
    val teacherName: String get() = parseTeacherNames(teacherRaw)

    /**
     * 是否已满。
     *
     * 复刻 Python `:509-514` 的取值优先级：优先 `jxbrs`（已选人数），
     * 缺失时回退 `yxzrs`，再用 `jxbrl`（容量）比较；任一解析失败即判定"未满"，
     * 不阻断用户尝试选课。
     */
    val isFull: Boolean
        get() {
            val selected = (enrolledCount.ifBlank { "" }).toDoubleOrNull() ?: return false
            val cap = capacity.toDoubleOrNull() ?: return false
            return cap > 0 && selected >= cap
        }

    /** 已选/容量展示串，如 "45/60" */
    val occupancyText: String
        get() = when {
            enrolledCount.isBlank() && capacity.isBlank() -> ""
            capacity.isBlank() -> "$enrolledCount 人"
            else -> "$enrolledCount/$capacity"
        }
}

/**
 * 子课程（教学班的组成部分）。
 *
 * 来源：`/xsxk/zzxkyzb_xkZyDisplayZzxkYzbZjxb.html`，接口直接返回 JSON 数组。
 *
 * 场景：一个教学班由"理论 + 实验"两个子课程组成时，浏览器点「选课」会弹出
 * 「选子课程」让用户勾选，最后把多个 `do_jxb_id` 逗号拼接提交。
 */
@Serializable
data class SubCourse(
    /** 子课程名，如"理论课"/"实验课" */
    @SerialName("xsmc") val subCourseName: String = "",
    @SerialName("kch_id") val courseId: String = "",
    @SerialName("kcmc") val courseName: String = "",
    @SerialName("jxb_id") val classId: String = "",
    /** 子课程提交 ID，参与 `jxb_ids` 拼接 */
    @SerialName("do_jxb_id") val doJxbId: String = "",
    @SerialName("jxbmc") val className: String = "",
    /** 教师原始串 `工号/姓名/职称` */
    @SerialName("jsxx") val teacherRaw: String = "",
    @SerialName("sksj") val classTime: String = "",
    @SerialName("jxdd") val classLocation: String = "",
    /** 已选人数（对应 `jxbrs`） */
    @SerialName("jxbrs") val enrolledCount: String = "",
    /** 容量（对应 `jxbrl`） */
    @SerialName("jxbrl") val capacity: String = "",
    @SerialName("rwzxs") val totalHours: String = "",
    @SerialName("xsdm") val studentTypeCode: String = ""
) {
    val teacherName: String get() = parseTeacherNames(teacherRaw)

    /** 是否已满，复刻 Python `:469` 的 `jxbrs >= jxbrl > 0` */
    val isFull: Boolean
        get() {
            val selected = enrolledCount.toDoubleOrNull() ?: return false
            val cap = capacity.toDoubleOrNull() ?: return false
            return cap > 0 && selected >= cap
        }

    val occupancyText: String
        get() = when {
            enrolledCount.isBlank() && capacity.isBlank() -> ""
            capacity.isBlank() -> "$enrolledCount 人"
            else -> "$enrolledCount/$capacity"
        }
}

/**
 * 已选课程（权威来源）。
 *
 * 来源：`/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html`。
 *
 * ## 为什么不复用课表接口
 *
 * 脚本 `:656-663` 明确警告：**课表只返回"有上课时间"的课程**，课程设计、
 * 实训、劳动教育等无排课课程不会出现，会导致已选课程漏显。
 * 因此已选清单**必须**以本接口为准。
 */
@Serializable
data class EnrolledCourse(
    @SerialName("kch") val courseCode: String = "",
    @SerialName("kch_id") val courseId: String = "",
    @SerialName("kcmc") val courseName: String = "",
    @SerialName("xf") val credits: String = "",
    @SerialName("jxb_id") val classId: String = "",
    /** 退选必需 */
    @SerialName("do_jxb_id") val doJxbId: String = "",
    @SerialName("jxbmc") val className: String = "",
    @SerialName("jsxx") val teacherRaw: String = "",
    @SerialName("sksj") val classTime: String = "",
    @SerialName("jxdd") val classLocation: String = "",
    @SerialName("xqumc") val campus: String = "",
    @SerialName("kklxmc") val categoryName: String = "",
    @SerialName("kklxdm") val typeCode: String = "",
    /** 志愿 */
    @SerialName("zy") val preference: String = "",
    /** 选上标记 */
    @SerialName("sxbj") val isSelected: String = "",
    @SerialName("kklxpx") val categoryOrder: String = ""
) {
    val teacherName: String get() = parseTeacherNames(teacherRaw)
}

/**
 * 课表条目（`kbList[]`），仅用于选课模块内展示每周安排与交叉校验。
 *
 * 复用现有 [com.xingheyuzhuan.shiguangschedule.data.network.CourseResponse] 作为外层容器。
 */
@Serializable
data class SelectionScheduleItem(
    @SerialName("kch") val courseCode: String = "",
    @SerialName("kch_id") val courseId: String = "",
    @SerialName("kcmc") val courseName: String = "",
    @SerialName("jxb_id") val classId: String = "",
    @SerialName("jxbmc") val className: String = "",
    /** 教师（课表接口用 `xm`，部分场景用 `jsxm`，取值时需回退） */
    @SerialName("xm") val teacherName: String = "",
    @SerialName("jsxm") val teacherNameAlt: String = "",
    /** 星期几，数字字符串 */
    @SerialName("xqj") val dayOfWeek: String = "",
    /** 节次，如 "1-2" */
    @SerialName("jcs") val sections: String = "",
    /** 起止周，如 "1-16周(单)" */
    @SerialName("zcd") val weekRange: String = "",
    @SerialName("cdmc") val classroom: String = "",
    @SerialName("xqmc") val campus: String = ""
) {
    val teacher: String get() = teacherName.ifBlank { teacherNameAlt }
}

/**
 * 选课轮次信息（对应 Python `round_info()`）。
 */
data class SelectionRoundInfo(
    val yearName: String = "",
    val termName: String = "",
    val roundId: String = "",
    /** 是否在选课时间内 —— 决定「选课」按钮是否可用 */
    val isInSelectionWindow: Boolean = false,
    val serverTime: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val maxCredits: String = "",
    val selectedCredits: String = "",
    val selectedCount: String = ""
) {
    /**
     * 学年学期展示串，如 "2024-2025学年 第一学期"。
     *
     * 教务下发的学期是**数字**（`1`/`2`/`3`），直接拼接会得到生硬的
     * "2026-2027 1"。此处翻译成中文序数；遇到非 1/2/3 的意外取值时
     * 原样回退，不做猜测。
     */
    val termDisplay: String
        get() {
            val yearPart = yearName.takeIf { it.isNotBlank() }?.let {
                if (it.contains("学年")) it else "$it 学年"
            }
            val termPart = when (termName.trim()) {
                "1" -> "第一学期"
                "2" -> "第二学期"
                "3" -> "第三学期"
                else -> termName.trim()
            }
            return listOf(yearPart.orEmpty(), termPart)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "—" }
        }
}

/**
 * 教务返回的选课/退选业务结果。
 *
 * ## 为什么不用异常表达业务失败
 *
 * 脚本用异常表达"网络失败"，用 `flag` 表达"业务失败"，两者语义不同：
 * - 网络失败 → 结果未知，需要重新登录或按未知处理
 * - 业务失败（flag=-1 名额已满等）→ 结果确定，是正常的选课结果
 *
 * 因此业务码一律通过本类型返回，**只有网络/会话级错误才抛异常**。
 */
sealed interface SelectionOutcome {

    /** 选课/退选成功 */
    data class Success(val flag: String, val rawMessage: String) : SelectionOutcome

    /** 重复选课（`flag == "6"`）—— 不是失败，课程已在课表中 */
    data class AlreadyEnrolled(val rawMessage: String) : SelectionOutcome

    /** 名额已满（`flag == "-1"`）—— 高并发下最常见的失败 */
    data class ClassFull(val rawMessage: String) : SelectionOutcome

    /** 会话失效或参数校验失败（`flag == "0"` / HTTP 911） */
    data class SessionExpired(val rawMessage: String) : SelectionOutcome

    /** 其他业务失败，[rawMessage] 为教务原文或 [`_FLAG_MSG`] 映射文案 */
    data class Failure(val flag: String, val rawMessage: String) : SelectionOutcome
}

// ═══════════════════════════════════════════════════════════════════════════
// 选课模块常量
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 解析教务教师原始串为显示名。
 *
 * 原始格式：`"1267/黄煜廉/讲师（高校）"`，多位教师用 `;` 分隔。
 * 规则（复刻 Python `_teacher_name`）：按 `/` 切分后取**第 2 段**作为姓名，
 * 段数不足时退化为第 1 段；`--` 视为空占位符剔除。
 *
 * @return 多位教师用 `、` 连接；无法解析时返回空串
 */
fun parseTeacherNames(teacherRaw: String): String {
    if (teacherRaw.isBlank()) return ""
    val names = mutableListOf<String>()
    for (part in teacherRaw.split(";")) {
        val segments = part.split("/").filter { it.isNotBlank() && it != "--" }
        when {
            segments.size >= 2 -> names.add(segments[1].trim())
            segments.isNotEmpty() -> names.add(segments[0].trim())
        }
    }
    return names.filter { it.isNotBlank() }.joinToString("、")
}

// ═══════════════════════════════════════════════════════════════════════════
// 课程同一性判定
//
// 「可选课程」与「已选课程」来自两个不同接口，同一门课在两个响应里的字段
// 填充程度并不一致（`kch_id` 可能一边为空、`kch` 格式可能有细微差异），
// 因此判断"这门课是否已选"必须**多口径兜底**，不能只比一个字段。
//
// 这也是"已选课程仍出现在可选列表"的根本原因：只比 `kch_id` 时，
// 一旦某侧该字段为空就永远匹配不上。
// ═══════════════════════════════════════════════════════════════════════════

/** 归一化课程标识：去空白、全角转半角括号、统一小写 */
private fun normalizeCourseKey(raw: String): String =
    raw.trim()
        .replace('（', '(')
        .replace('）', ')')
        .lowercase()

/**
 * 判断两个课程标识是否指向同一门课。
 *
 * 空串**不参与比较**（避免"两边都为空"被误判为同一门课）。
 */
private fun sameCourseKey(a: String, b: String): Boolean {
    val na = normalizeCourseKey(a)
    val nb = normalizeCourseKey(b)
    return na.isNotEmpty() && na == nb
}

/**
 * 判断某门可选课程是否已在已选清单中。
 *
 * ## 多口径 + 课程名交叉校验
 *
 * 「可选课程」与「已选课程」来自两个不同接口，同一门课在两个响应里的字段
 * 填充程度并不一致（`kch_id` 可能一侧为空、`kch` 格式可能有细微差异），
 * 因此判定必须层层兜底：
 *
 * 1. `kch_id` 精确匹配
 * 2. `kch` 精确匹配 **且** 课程名不冲突（防止课程号复用造成的误判）
 * 3. `kch` 精确匹配（课程名双方都缺失时的兜底）
 *
 * 空串一律不参与比较，避免"两边都为空"被误判为同一门课。
 *
 * @param selectableCourseId 可选课程的 `kch_id`
 * @param selectableCourseCode 可选课程的 `kch`
 * @param selectableCourseName 可选课程的 `kcmc`，用于交叉校验
 * @param enrolled 已选清单
 */
fun isCourseEnrolled(
    selectableCourseId: String,
    selectableCourseCode: String,
    selectableCourseName: String,
    enrolled: List<EnrolledCourse>
): Boolean {
    val id = normalizeCourseKey(selectableCourseId)
    val code = normalizeCourseKey(selectableCourseCode)
    val name = normalizeCourseKey(selectableCourseName)

    return enrolled.any { e ->
        val eId = normalizeCourseKey(e.courseId)
        val eCode = normalizeCourseKey(e.courseCode)
        val eName = normalizeCourseKey(e.courseName)

        when {
            // 1. 内部 ID 精确匹配（最强证据）
            id.isNotEmpty() && id == eId -> true

            // 2. 课程号匹配，且课程名不冲突
            code.isNotEmpty() && code == eCode ->
                name.isEmpty() || eName.isEmpty() || name == eName

            else -> false
        }
    }
}

/**
 * 判断某门可选课程自身是否已在已选清单中（便捷重载）。
 */
fun SelectableCourse.isEnrolledIn(enrolled: List<EnrolledCourse>): Boolean =
    isCourseEnrolled(courseId, courseCode, courseName, enrolled)
