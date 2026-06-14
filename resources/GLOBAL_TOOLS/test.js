/**
 * 华南师范大学（正方教务系统）成绩抓取测试脚本 - 接口直连版
 *
 * 运行环境：Android WebView（已登录状态，Session/Cookie 自动携带）
 * 功能：直接请求成绩查询接口，解析 JSON 并返回成绩列表
 * 输出：alert 弹出 JSON，并通过 AndroidBridge.showToast 提示数量
 */

(function () {
    "use strict";

    /**
     * 收集查询参数：序列化页面 searchForm 的所有字段
     * 这样 hidden input（jsxx, jslxdm, yhm, sxxdm, bj, xscjcksz 等）
     * 和可见下拉框（xnm, xqm, kcbjdm 等）都会被自动包含
     */
    function getQueryParams() {
        var form = document.getElementById("searchForm");
        if (!form) {
            AndroidBridge.showToast("未找到 searchForm，请确认页面是否完整加载");
            return null;
        }

        var params = new URLSearchParams();

        // 方式1：优先使用 FormData（不支持的浏览器回退到方式2）
        if (typeof FormData !== "undefined") {
            var formData = new FormData(form);
            formData.forEach(function (value, key) {
                params.append(key, value);
            });
        } else {
            // 方式2：手动遍历表单元素
            var elements = form.elements;
            for (var i = 0; i < elements.length; i++) {
                var el = elements[i];
                if (el.name && el.name !== "" && el.type !== "submit") {
                    params.append(el.name, el.value);
                }
            }
        }

        // 每页条数设大一些，尽量一次取完
        params.set("page", "1");
        params.set("rows", "1000");

        return params;
    }

    /**
     * 解析接口返回的 JSON 数据，提取成绩列表
     * 实际响应格式：{ items: [{ kcmc, xf, cj, jd, ... }], totalResult: 13, ... }
     * @param {Object} json - 接口返回的 JSON 对象
     * @returns {Array} 成绩数组 [{ courseName, credit, score, gpa }]
     */
    function parseResponseJson(json) {
        // 方式1：标准返回 { items: [...] }
        if (json.items && Array.isArray(json.items) && json.items.length > 0) {
            return json.items.map(function (item) {
                return {
                    courseName: (item.kcmc || "").trim(),
                    credit: (item.xf || "").toString().trim(),
                    score: (item.cj || "").toString().trim(),
                    gpa: (item.jd || "").toString().trim()
                };
            });
        }

        // 方式2：直返数组
        if (Array.isArray(json) && json.length > 0) {
            return json.map(function (item) {
                return {
                    courseName: (item.kcmc || item.courseName || "").trim(),
                    credit: (item.xf || item.credit || "").toString().trim(),
                    score: (item.cj || item.score || "").toString().trim(),
                    gpa: (item.jd || item.gpa || "").toString().trim()
                };
            });
        }

        // 方式3：标准 jqGrid 格式 { rows: [{ cell: [...] }] }
        // 注意：cell 中各列顺序由 colModel 决定，此处仅作兜底
        if (json.rows && Array.isArray(json.rows) && json.rows.length > 0) {
            var results = [];
            for (var i = 0; i < json.rows.length; i++) {
                var cells = json.rows[i].cell;
                if (cells && cells.length >= 4) {
                    results.push({
                        courseName: cells[0] ? cells[0].trim() : "",
                        credit: cells[1] ? cells[1].trim() : "",
                        score: cells[2] ? cells[2].trim() : "",
                        gpa: cells[3] ? cells[3].trim() : ""
                    });
                }
            }
            return results;
        }

        // 兜底：未知格式，包裹到 alert 里供调试
        console.warn("未知 JSON 结构", json);
        return [];
    }

    /**
     * 发送请求获取成绩数据
     */
    function fetchScores() {
        var params = getQueryParams();
        if (!params) return;

        console.log("请求参数:", params.toString());

        fetch("/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Requested-With": "XMLHttpRequest"
            },
            body: params.toString(),
            credentials: "include"
        })
        .then(function (response) {
            if (!response.ok) {
                throw new Error("HTTP " + response.status);
            }
            return response.json();
        })
        .then(function (data) {
            var results = parseResponseJson(data);

            if (results.length === 0) {
                AndroidBridge.showToast("未解析到成绩数据");
                // 输出原始数据供调试
                alert("无法解析成绩，原始响应：\n" + JSON.stringify(data, null, 2));
                return;
            }

            var output = JSON.stringify(results, null, 2);
            console.log("成功抓取 " + results.length + " 条成绩");
            AndroidBridge.showToast("共抓取到 " + results.length + " 条成绩数据");
            alert(output);
        })
        .catch(function (error) {
            console.error("请求失败:", error);
            AndroidBridge.showToast("请求成绩接口失败: " + error.message);
        });
    }

    fetchScores();
})();
