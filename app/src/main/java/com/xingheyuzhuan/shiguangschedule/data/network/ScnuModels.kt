package com.xingheyuzhuan.shiguangschedule.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * 成绩查询 API 返回的 JSON 结构
 */
@Serializable
data class GradeResponse(
    val items: List<GradeItem> = emptyList()
)

/**
 * 单条成绩记录，字段映射自 SCNU 教务系统 JSON API 的 items[] 元素。
 * 所有字段使用 String 类型，因为后端可能返回空字符串、文字评语（如"优秀""良好"）等非纯数字。
 */
@Serializable
data class GradeItem(
    @SerialName("xnmmc") val xnmmc: String = "",      // 学年（如 "2024-2025"）
    @SerialName("xqmmc") val xqmmc: String = "",      // 学期（如 "第一学期"）
    @SerialName("kcmc") val kcmc: String = "",        // 课程名称
    @SerialName("kch") val kch: String = "",          // 课程代码
    @SerialName("kcxzmc") val kcxzmc: String = "",    // 课程性质（必修/选修等）
    @SerialName("xf") val xf: String = "",            // 学分
    @SerialName("cj") val cj: String = "",            // 成绩（可能为数字、"优秀"等文字）
    @SerialName("jd") val jd: String = "",            // 绩点
    @SerialName("khfsmc") val khfsmc: String = "",    // 考核方式
    @SerialName("jsxm") val jsxm: String = "",        // 任课教师
    @SerialName("kkbmmc") val kkbmmc: String = ""     // 开课学院
)

/**
 * 考试信息 API 返回的 JSON 结构
 */
@Serializable
data class ExamResponse(
    val items: List<ExamItem> = emptyList()
)

/**
 * 单条考试安排，字段映射自 SCNU 教务系统 JSON API 的 items[] 元素。
 */
@Serializable
data class ExamItem(
    @SerialName("xqm") val xqm: String = "",          // 学年学期（如 "2024-2025-1"）
    @SerialName("ksmc") val ksmc: String = "",        // 考试名称
    @SerialName("kcmc") val kcmc: String = "",        // 课程名称
    @SerialName("kssj") val kssj: String = "",        // 考试时间（如 "2025-01-10 09:00"）
    @SerialName("cdmc") val cdmc: String = "",        // 场地名称（教学楼）
    @SerialName("cdbh") val cdbh: String = "",        // 场地编号（教室号）
    @SerialName("cdxqmc") val cdxqmc: String = "",    // 校区
    @SerialName("ksfs") val ksfs: String = "",        // 考试方式
    @SerialName("khfs") val khfs: String = "",        // 考核方式
    @SerialName("kkxy") val kkxy: String = ""         // 开课学院
)
