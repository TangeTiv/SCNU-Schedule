package com.xingheyuzhuan.shiguangschedule.data.db.main

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xingheyuzhuan.shiguangschedule.data.network.GradeItem

/**
 * Room 实体类，代表"成绩"数据表。
 * 字段全部使用 String 类型，因为教务系统可能返回文字评语（如"优秀""良好"）等非纯数字值。
 */
@Entity(tableName = "grades")
data class GradeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val xnmmc: String = "",      // 学年（如 "2024-2025"）
    val xqmmc: String = "",      // 学期（如 "第一学期"）
    val kcmc: String = "",       // 课程名称
    val kch: String = "",        // 课程代码
    val kcxzmc: String = "",     // 课程性质（必修/选修等）
    val xf: String = "",         // 学分
    val cj: String = "",         // 成绩（可能为数字、"优秀"等文字）
    val jd: String = "",         // 绩点
    val khfsmc: String = "",     // 考核方式
    val jsxm: String = "",       // 任课教师
    val kkbmmc: String = ""      // 开课学院
)

/**
 * 将网络层 [GradeItem] 转换为 Room 实体 [GradeEntity]。
 * id 不设置（默认为 0），由数据库自增生成。
 */
fun GradeItem.toEntity(): GradeEntity = GradeEntity(
    xnmmc = xnmmc,
    xqmmc = xqmmc,
    kcmc = kcmc,
    kch = kch,
    kcxzmc = kcxzmc,
    xf = xf,
    cj = cj,
    jd = jd,
    khfsmc = khfsmc,
    jsxm = jsxm,
    kkbmmc = kkbmmc
)
