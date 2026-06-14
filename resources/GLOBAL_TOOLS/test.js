/**
 * 华南师范大学（正方教务系统）成绩抓取测试脚本
 *
 * 运行环境：Android WebView
 * 可用桥接：AndroidBridge.showToast()、alert()
 *
 * 功能：遍历成绩页面表格，提取课程名称、学分、成绩、绩点，
 *       以 JSON 格式弹窗展示结果。
 */

(function () {
    "use strict";

    /**
     * 从一行 tr 元素中解析成绩数据
     * @param {HTMLTableRowElement} tr - 表格行元素
     * @returns {Object|null} 解析出的成绩对象，解析失败返回 null
     */
    function parseRow(tr) {
        const cells = tr.querySelectorAll("td");
        // 过滤表头、空行：有效成绩行列数通常较多
        if (cells.length <= 5) return null;

        try {
            const courseName = cells[0].textContent.trim();
            // 学分通常在前半部分，从第 1 列往后找第一个数值文本
            let credit = "";
            for (let i = 1; i < cells.length - 2; i++) {
                const text = cells[i].textContent.trim();
                if (/^\d+(\.\d+)?$/.test(text)) {
                    credit = text;
                    break;
                }
            }
            // 成绩在倒数第 2 列，绩点在最后 1 列
            const score = cells[cells.length - 2].textContent.trim();
            const gpa = cells[cells.length - 1].textContent.trim();

            // 跳过成绩为空或为标题文本的无效行
            if (!score || /^[一-龥]+$/.test(score)) return null;

            return { courseName, credit, score, gpa };
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

        const rows = document.querySelectorAll("table tbody tr");

        if (!rows || rows.length === 0) {
            AndroidBridge.showToast("未找到成绩表格");
            console.warn("未找到成绩表格，请确认页面是否包含成绩数据。");
            return;
        }

        const results = [];
        let parseErrorCount = 0;

        rows.forEach(function (tr) {
            const record = parseRow(tr);
            if (record) {
                results.push(record);
            } else {
                parseErrorCount++;
            }
        });

        if (results.length === 0) {
            AndroidBridge.showToast("未解析到有效成绩数据");
            console.warn("遍历了 " + rows.length + " 行，但未解析到有效成绩，请检查表格结构。");
            return;
        }

        const output = JSON.stringify(results, null, 2);
        console.log("成功解析 " + results.length + " 条成绩记录，过滤 " + parseErrorCount + " 行。");
        AndroidBridge.showToast("共抓取到 " + results.length + " 条成绩数据");
        alert(output);
    }

    runScoreScraper();
})();
