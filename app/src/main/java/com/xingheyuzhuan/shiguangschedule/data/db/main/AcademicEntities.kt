package com.xingheyuzhuan.shiguangschedule.data.db.main

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.xingheyuzhuan.shiguangschedule.data.network.AcademicCourseDto
import com.xingheyuzhuan.shiguangschedule.data.network.NonFormalCourseDto
import com.xingheyuzhuan.shiguangschedule.data.network.PlanNodeDto

// ═══════════════════════════════════════════════════════════════════════════
// 学业情况（培养计划）本地缓存实体
//
// 数据来源：华南师范大学教务系统「学生学业情况查询」模块（gnmkdm=N105515）
// 与「第二类课成绩查询」模块（gnmkdm=N305012）。
//
// 设计原则：**只存层级树的原始事实，不存任何 UI 分界信息**。
// 哪些节点当一级标签、哪些当二级标签、哪些当折叠面板，全部由 UI 层读取时
// 按 depth 现算（见 ui/campus/AcademicTreeBuilder.kt）。这样换一个年级/学院
// （培养计划层级不同）时无需改动任何代码，也无需重新同步。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 培养计划点（教务树形结构中的一个节点）。
 *
 * 树形关系由 [parentId] 自引用表达；根节点（通识教育/大类教育/专业教育/实践教育）
 * 的 [parentId] 为 null。
 *
 * [id] 直接使用教务下发的 32 位十六进制 ID（形如
 * `51AF705881855A11E0630B00F80AD6ED`），因此天然唯一，无需自增主键。
 *
 * @param requiredCredits 要求学分
 * @param earnedCredits 已获得学分
 * @param passed 教务侧的「是否通过」标记（sftg=1 为通过）
 * @param sortOrder 教务源码中的出现顺序，用于稳定还原树的展示次序
 */
@Entity(
    tableName = "academic_plan_nodes",
    indices = [Index("parentId"), Index("sortOrder")]
)
data class AcademicPlanNodeEntity(
    @PrimaryKey
    val id: String,
    val parentId: String? = null,
    val name: String = "",
    val requiredCredits: Float = 0f,
    val earnedCredits: Float = 0f,
    val passed: Boolean = false,
    val sortOrder: Int = 0
)

/**
 * 培养计划点下的课程明细。
 *
 * 课程在教务侧**只挂载于「叶子节点的公共祖先」**上，例如「大类必修」直接挂着
 * 14 门课，而它的父节点「大类教育」为 0 门、且没有子节点。因此 UI 层判断
 * 「该节点是否可展开」时必须同时看 [AcademicPlanNodeEntity] 的 children 与
 * 本表的课程数量，不能只看树深度。
 *
 * [id] 自增：每次同步整表替换，课程本身没有稳定业务主键。
 *
 * @param readStatusCode 教务原始修读状态码（1=在修 / 2=未修 / 3=未修 / 4=已修）
 * @param readStatus 已中文化的修读状态
 * @param academicYear 成绩学年，形如 "2024-2025"；未修课程为空串
 * @param term 学期，形如 "1"；未修课程为空串
 */
@Entity(
    tableName = "academic_courses",
    indices = [Index("nodeId")]
)
data class AcademicCourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val nodeId: String = "",
    val courseCode: String = "",
    val courseName: String = "",
    val credits: String = "",
    val score: String = "",
    val maxScore: String = "",
    val gpa: String = "",
    val readStatusCode: String = "",
    val readStatus: String = "",
    val academicYear: String = "",
    val term: String = "",
    val courseNature: String = "",
    val courseCategory: String = "",
    val hoursComposition: String = "",
    val isInPlan: String = ""
)

/**
 * 第二类课（非正式学时）记录。
 *
 * 对应教务「第二类课成绩查询」模块（gnmkdm=N305012），即【学业情况】页
 * 「非正式学时」标签页的数据来源。
 *
 * 注意：第二类课的 [credits] 实测恒为 "0"（非正式课程不计学分），这是**正常现象**，
 * 学时数看 [hours]（教务字段 `xssh`）。
 *
 * @param result 结论字段，形如 "通过" / "不通过" / 分数
 * @param rawScore 原始分（教务字段 `bfzcj`）。实测存在 `result="通过"` 而
 *                 `rawScore="75"` 的情况，两层分数都要保留
 * @param scoreNote 成绩备注（教务字段 `cjbz`）
 * @param hours 学时数（教务字段 `xssh`），「时长」即取此值
 * @param ownership 课程归属，形如 "创新创业（非正式）"
 */
@Entity(
    tableName = "academic_nonformal_courses",
    indices = [Index(value = ["academicYear", "term"])]
)
data class AcademicNonFormalCourseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val courseCode: String = "",
    val courseName: String = "",
    val result: String = "",
    val rawScore: String = "",
    val scoreNote: String = "",
    val credits: String = "",
    val hours: String = "",
    val category: String = "",
    val ownership: String = "",
    val teacher: String = "",
    val academicYear: String = "",
    val term: String = ""
)

// ═══════════════════════════════════════════════════════════════════════════
// 网络 DTO → Room 实体
// ═══════════════════════════════════════════════════════════════════════════

/** 把网络层的计划点 DTO 映射为 Room 实体。 */
fun PlanNodeDto.toEntity(sortOrder: Int): AcademicPlanNodeEntity = AcademicPlanNodeEntity(
    id = id,
    parentId = parentId,
    name = name,
    requiredCredits = requiredCredits,
    earnedCredits = earnedCredits,
    passed = passed,
    sortOrder = sortOrder
)

/** 把网络层的培养计划课程 DTO 映射为 Room 实体。 */
fun AcademicCourseDto.toEntity(nodeId: String): AcademicCourseEntity = AcademicCourseEntity(
    nodeId = nodeId,
    courseCode = courseCode,
    courseName = courseName,
    credits = credits,
    score = score,
    maxScore = maxScore,
    gpa = gpa,
    readStatusCode = readStatusCode,
    readStatus = readStatus,
    academicYear = academicYear,
    term = term,
    courseNature = courseNature,
    courseCategory = courseCategory,
    hoursComposition = hoursComposition,
    isInPlan = isInPlan
)

/** 把网络层的第二类课 DTO 映射为 Room 实体。 */
fun NonFormalCourseDto.toEntity(): AcademicNonFormalCourseEntity =
    AcademicNonFormalCourseEntity(
        courseCode = courseCode,
        courseName = courseName,
        result = result,
        rawScore = rawScore,
        scoreNote = scoreNote,
        credits = credits,
        hours = hours,
        category = category,
        ownership = ownership,
        teacher = teacher,
        academicYear = academicYear,
        term = term
    )
