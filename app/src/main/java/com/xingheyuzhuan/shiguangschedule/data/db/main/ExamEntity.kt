package com.xingheyuzhuan.shiguangschedule.data.db.main

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.xingheyuzhuan.shiguangschedule.data.network.ExamItem

/**
 * Room 实体类，代表"考试安排"数据表。
 * 所有字段使用 String 类型，与教务系统 API 返回的数据格式保持一致。
 */
@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val xqm: String = "",        // 学年学期（如 "2024-2025-1"）
    val ksmc: String = "",       // 考试名称
    val kcmc: String = "",       // 课程名称
    val kssj: String = "",       // 考试时间（如 "2025-01-10 09:00"）
    val cdmc: String = "",       // 场地名称（教学楼）
    val cdbh: String = "",       // 场地编号（教室号）
    val cdxqmc: String = "",     // 校区
    val ksfs: String = "",       // 考试方式
    val khfs: String = "",       // 考核方式
    val kkxy: String = ""        // 开课学院
)

/**
 * 将网络层 [ExamItem] 转换为 Room 实体 [ExamEntity]。
 * id 不设置（默认为 0），由数据库自增生成。
 */
fun ExamItem.toEntity(): ExamEntity = ExamEntity(
    xqm = xqm,
    ksmc = ksmc,
    kcmc = kcmc,
    kssj = kssj,
    cdmc = cdmc,
    cdbh = cdbh,
    cdxqmc = cdxqmc,
    ksfs = ksfs,
    khfs = khfs,
    kkxy = kkxy
)
