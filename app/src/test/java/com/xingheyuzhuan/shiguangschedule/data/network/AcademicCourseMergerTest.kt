package com.xingheyuzhuan.shiguangschedule.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AcademicCourseMerger] 的测试。
 *
 * 锁住的是「**两个接口都查 + 去重**」这个行为。
 *
 * 背景：教务把同一计划点的课程拆在 `Kcxx`（计划内）与 `FKcxx`（计划外）
 * 两个接口返回，**部分节点的课只在前者或只在后者**。只查一个接口会漏课，
 * 最典型的是「通识选修」——`Kcxx` 返回空，课程全在 `FKcxx`。
 *
 * 同时两个接口在多数节点上返回重叠内容，所以去重也是必须的：
 * 不去重课程数会翻倍。
 */
class AcademicCourseMergerTest {

    private fun course(
        code: String,
        name: String = "课程",
        year: String = "2024-2025",
        term: String = "1",
        score: String = "84"
    ) = AcademicCourseDto(
        courseCode = code,
        courseName = name,
        gradedYear = year,
        gradedTerm = term,
        score = score
    )

    // ═══════════════════════════════════════════════════════════════════════
    // 两个接口合并
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `keeps in-plan courses when there are no extra courses`() {
        val inPlan = listOf(course("A"), course("B"))
        val merged = AcademicCourseMerger.merge(inPlan, emptyList())
        assertEquals(2, merged.size)
        assertEquals(listOf("A", "B"), merged.map { it.courseCode })
    }

    @Test
    fun `adds extra courses that are not in plan`() {
        // 通识选修的情形：计划内为空，课程全在计划外
        val extra = listOf(
            course("X1", "博弈策略思维", score = "94"),
            course("X2", "创新创业之创意技术", score = "87")
        )
        val merged = AcademicCourseMerger.merge(emptyList(), extra)
        assertEquals(2, merged.size)
        assertEquals(
            listOf("博弈策略思维", "创新创业之创意技术"),
            merged.map { it.courseName }
        )
    }

    @Test
    fun `merges disjoint sets from both endpoints`() {
        val inPlan = listOf(course("A"), course("B"))
        val extra = listOf(course("C"), course("D"))
        val merged = AcademicCourseMerger.merge(inPlan, extra)
        assertEquals(4, merged.size)
        // 保持顺序：先计划内，再计划外
        assertEquals(listOf("A", "B", "C", "D"), merged.map { it.courseCode })
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 去重
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `deduplicates overlapping courses`() {
        // 实测思政军事与体育：Kcxx 13 条 / FKcxx 12 条，几乎完全重叠
        val inPlan = listOf(course("A"), course("B"), course("C"))
        val extra = listOf(course("A"), course("B"), course("C"))
        val merged = AcademicCourseMerger.merge(inPlan, extra)
        assertEquals(3, merged.size)
    }

    @Test
    fun `partial overlap keeps in-plan and appends only new extras`() {
        // 实测四史：Kcxx 4 条 / FKcxx 1 条（社会主义发展史是新增的）
        val inPlan = listOf(course("A"), course("B"), course("C"), course("D"))
        val extra = listOf(course("A"), course("E", "社会主义发展史"))
        val merged = AcademicCourseMerger.merge(inPlan, extra)
        assertEquals(5, merged.size)
        assertTrue(merged.any { it.courseName == "社会主义发展史" })
    }

    @Test
    fun `in-plan record wins on conflict`() {
        val inPlan = listOf(course("A", name = "计划内版本", score = "84"))
        val extra = listOf(course("A", name = "计划外版本", score = "84"))
        val merged = AcademicCourseMerger.merge(inPlan, extra)
        assertEquals(1, merged.size)
        assertEquals("计划内版本", merged[0].courseName)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 去重键的四个组成部分
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `same course in different terms is not deduplicated`() {
        val inPlan = listOf(course("A", year = "2024-2025", term = "1"))
        val extra = listOf(course("A", year = "2025-2026", term = "2"))
        assertEquals(2, AcademicCourseMerger.merge(inPlan, extra).size)
    }

    @Test
    fun `same course with different score is not deduplicated`() {
        // 重修场景：同一门课两次成绩都该保留
        val inPlan = listOf(course("A", score = "84"))
        val extra = listOf(course("A", score = "90"))
        assertEquals(2, AcademicCourseMerger.merge(inPlan, extra).size)
    }

    @Test
    fun `same course with same term and score is deduplicated`() {
        val inPlan = listOf(course("A", year = "2024-2025", term = "1", score = "84"))
        val extra = listOf(course("A", year = "2024-2025", term = "1", score = "84"))
        assertEquals(1, AcademicCourseMerger.merge(inPlan, extra).size)
    }

    @Test
    fun `dedup key uses resolved year and term not raw fields`() {
        // 未修课程只有建议修读学年；去重键必须用解析后的值，
        // 否则 FKcxx 里同一门课会因为字段来源不同而被当成两门
        val a = AcademicCourseDto(
            courseCode = "A",
            plannedYear = "2024-2025",
            plannedTerm = "3"
        )
        val b = AcademicCourseDto(
            courseCode = "A",
            gradedYear = "2024-2025",
            gradedTerm = "1"
        )
        // plannedTerm "3" 归一为 "1"，与 gradedTerm "1" 相同 → 应当去重
        assertEquals(AcademicCourseMerger.dedupKey(a), AcademicCourseMerger.dedupKey(b))
    }

    @Test
    fun `both empty yields empty result`() {
        assertTrue(AcademicCourseMerger.merge(emptyList(), emptyList()).isEmpty())
    }
}
