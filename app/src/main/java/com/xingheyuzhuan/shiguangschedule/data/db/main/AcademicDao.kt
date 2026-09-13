package com.xingheyuzhuan.shiguangschedule.data.db.main

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 学业情况（培养计划 + 第二类课）数据访问对象。
 *
 * 三张表都采用「全量刷新」语义：教务侧每次下发的是完整快照，
 * 因此同步时先清空再整体写入，避免残留旧数据造成学分统计错乱。
 */
@Dao
interface AcademicDao {

    // ─────────────────────────────────────────────────────────────────────
    // 培养计划点
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 观察全部培养计划点。
     *
     * 按 [AcademicPlanNodeEntity.sortOrder] 升序返回，这个顺序即教务源码中
     * 深度优先遍历的出现顺序，UI 层据此稳定还原树的展示次序。
     */
    @Query("SELECT * FROM academic_plan_nodes ORDER BY sortOrder ASC")
    fun observePlanNodes(): Flow<List<AcademicPlanNodeEntity>>

    @Query("DELETE FROM academic_plan_nodes")
    suspend fun deleteAllPlanNodes()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlanNodes(items: List<AcademicPlanNodeEntity>)

    /**
     * 全量刷新培养计划点：事务内先清空再写入。
     *
     * 传入空列表时**不会**执行清空 —— 避免因一次同步失败把用户已有的数据抹掉。
     */
    @Transaction
    suspend fun replacePlanNodes(items: List<AcademicPlanNodeEntity>) {
        if (items.isEmpty()) return
        deleteAllPlanNodes()
        insertPlanNodes(items)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 培养计划课程
    // ─────────────────────────────────────────────────────────────────────

    /** 观察全部培养计划课程（按课程名排序，保证展示稳定）。 */
    @Query("SELECT * FROM academic_courses ORDER BY courseName ASC")
    fun observeCourses(): Flow<List<AcademicCourseEntity>>

    @Query("DELETE FROM academic_courses")
    suspend fun deleteAllCourses()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(items: List<AcademicCourseEntity>)

    /** 全量刷新课程明细，语义同 [replacePlanNodes]。 */
    @Transaction
    suspend fun replaceCourses(items: List<AcademicCourseEntity>) {
        if (items.isEmpty()) return
        deleteAllCourses()
        insertCourses(items)
    }

    // ─────────────────────────────────────────────────────────────────────
    // 第二类课（非正式学时）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 观察全部第二类课记录。
     *
     * 按学年、学期升序排列，形成「入学以来」的时间顺序；
     * 同一学期内按课程名排序保证展示稳定。
     */
    @Query(
        "SELECT * FROM academic_nonformal_courses " +
                "ORDER BY academicYear ASC, term ASC, courseName ASC"
    )
    fun observeNonFormalCourses(): Flow<List<AcademicNonFormalCourseEntity>>

    @Query("DELETE FROM academic_nonformal_courses")
    suspend fun deleteAllNonFormalCourses()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNonFormalCourses(items: List<AcademicNonFormalCourseEntity>)

    /** 全量刷新第二类课记录，语义同 [replacePlanNodes]。 */
    @Transaction
    suspend fun replaceNonFormalCourses(items: List<AcademicNonFormalCourseEntity>) {
        if (items.isEmpty()) return
        deleteAllNonFormalCourses()
        insertNonFormalCourses(items)
    }
}
