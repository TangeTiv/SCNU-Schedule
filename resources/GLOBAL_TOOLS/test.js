/**
 * 华南师范大学（正方教务系统）成绩抓取测试脚本
 *
 * 运行环境：Android WebView
 * 可用桥接：AndroidBridge.showToast()、alert()
 *
 * 功能：遍历 jqGrid 成绩表格，智能识别课程名称、提取学分、成绩、绩点，
 *       以 JSON 格式弹窗展示结果。
 */

(function () {
    "use strict";

    /**
     * 判断文本是否为纯数字（含小数点）
     */
    function isNumeric(text) {
        return /^\d+(\.\d+)?$/.test(text);
    }

    /**
     * 判断文本是否为学年学期格式（如 "2025-2026"、"2025-2026学年"）
     */
    function isYearSemester(text) {
        return /^\d{4}\s*-\s*\d{4}/.test(text);
    }

    /**
     * 从一行 td 列表中智能识别课程名称
     * 跳过：纯数字、学年学期、过短的标签文本
     * @param {NodeList} cells - td 元素列表
     * @returns {string} 识别到的课程名称，未找到返回空字符串
     */
    function findCourseName(cells) {
        for (let i = 0; i < cells.length; i++) {
            const text = cells[i].textContent.trim();
            // 跳过空文本
            if (!text) continue;
            // 跳过纯数字（序号、学号等）
            if (isNumeric(text)) continue;
            // 跳过学年学期格式
            if (isYearSemester(text)) continue;
            // 跳过过短的标签文本（复选框列、下拉选项等）
            if (text.length < 2) continue;
            // 跳过常见短标签
            if (/^(全部|主修|辅修|学位|微专业|非学位|二学位)$/.test(text)) continue;
            // 第一个符合条件的就是课程名
            return text;
        }
        return "";
    }

    /**
     * 从一行 tr 元素中解析成绩数据
     * @param {HTMLTableRowElement} tr - jqGrid 数据行元素
     * @returns {Object|null} 解析出的成绩对象，无效行返回 null
     */
    function parseRow(tr) {
        const cells = tr.querySelectorAll("td");
        if (cells.length === 0) return null;

        try {
            // 智能识别课程名称
            const courseName = findCourseName(cells);

            // 过滤空课程名行
            if (!courseName) return null;
            // 过滤合计/总计/平均行
            if (/合计|总计|平均/i.test(courseName)) return null;

            // 学分：从前往后找第一个数值列
            let credit = "";
            for (let i = 0; i < cells.length - 2; i++) {
                const text = cells[i].textContent.trim();
                if (isNumeric(text) && parseFloat(text) <= 30) {
                    credit = text;
                    break;
                }
            }

            // 成绩在倒数第 2 列，绩点在最后 1 列
            const score = cells[cells.length - 2].textContent.trim();
            const gpa = cells[cells.length - 1].textContent.trim();

            return { courseName: courseName, credit: credit, score: score, gpa: gpa };
        } catch (rowError) {
            console.error("解析单行数据时出错:", rowError);
            return null;
        }
    }

    /**
     * 主函数：抓取成绩数据
     */
    function runScoreScraper() {
        console.log("成绩抓取脚本开始执行...");

        // jqGrid 数据行带有 jqgrow 类
        var rows = document.querySelectorAll("#tabGrid tr.jqgrow");
        // 兜底：兼容不同的 jqGrid 版本
        if (!rows || rows.length === 0) {
            rows = document.querySelectorAll(".ui-jqgrid-btable tbody tr.jqgrow");
        }

        if (!rows || rows.length === 0) {
            AndroidBridge.showToast("未找到成绩表格");
            console.warn("未找到 jqGrid 成绩表格（#tabGrid tr.jqgrow），请确认页面是否包含成绩数据。");
            return;
        }

        var results = [];
        var parseErrorCount = 0;

        Array.prototype.forEach.call(rows, function (tr) {
            var record = parseRow(tr);
            if (record) {
                results.push(record);
            } else {
                parseErrorCount++;
            }
        });

        if (results.length === 0) {
            AndroidBridge.showToast("未解析到有效成绩数据");
            console.warn("遍历了 " + rows.length + " 行，但未解析到有效成绩，请检查 jqGrid 列结构。");
            return;
        }

        var output = JSON.stringify(results, null, 2);
        console.log("成功解析 " + results.length + " 条成绩记录，过滤 " + parseErrorCount + " 行。");
        AndroidBridge.showToast("共抓取到 " + results.length + " 条成绩数据");
        alert(output);
    }

    runScoreScraper();
})();
