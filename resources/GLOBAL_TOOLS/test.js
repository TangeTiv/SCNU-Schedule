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

        // 与服务端默认 pageSize 保持一致
        params.set("rows", "15");

        return params;
    }

    /**
     * 解析 items 数组中的单条记录为统一格式
     */
    function extractItem(item) {
        return {
            courseName: (item.kcmc || "").trim(),
            credit: (item.xf || "").toString().trim(),
            score: (item.cj || "").toString().trim(),
            gpa: (item.jd || "").toString().trim()
        };
    }

    /**
     * 解析接口返回的 JSON 数据，提取成绩列表
     * @param {Object} json - 接口返回的 JSON 对象
     * @returns {Array} 成绩数组，或 null（格式未知）
     */
    function parseResponseJson(json) {
        // 方式1：{ rows: [{ cell: [...] }] } jqGrid 标准格式
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

        // 方式2：直返数组
        if (Array.isArray(json)) {
            return json.map(function (item) {
                return {
                    courseName: (item.kcmc || item.courseName || "").trim(),
                    credit: (item.xf || item.credit || "").toString().trim(),
                    score: (item.cj || item.score || "").toString().trim(),
                    gpa: (item.jd || item.gpa || "").toString().trim()
                };
            });
        }

        // 其他格式（含 { items: [...] }）—— 返回 null，由外层统一用 extractItem 逐条处理
        return null;
    }

    /**
     * 请求第 page 页，结果追加到 allItems 数组
     * @param {number} page - 页码（从 1 开始）
     * @param {Array} allItems - 用于累计所有页的原始 item 对象
     * @param {number} totalResult - 总记录数（仅首次传入，后续递归沿用）
     * @returns {Promise} 解析完成后 resolve
     */
    function fetchPage(page, allItems, totalResult) {
        var params = getQueryParams();
        params.set("page", page.toString());

        return fetch("/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Requested-With": "XMLHttpRequest"
            },
            body: params.toString(),
            credentials: "include"
        })
        .then(function (response) {
            if (!response.ok) throw new Error("HTTP " + response.status);
            return response.json();
        })
        .then(function (data) {
            // 追加当前页的原始数据
            if (data.items && Array.isArray(data.items)) {
                for (var i = 0; i < data.items.length; i++) {
                    allItems.push(data.items[i]);
                }
            }

            // 首次请求时读取分页信息
            if (totalResult === undefined) {
                totalResult = data.totalResult || data.totalCount || 0;
                var pageSize = data.pageSize || data.showCount || 15;
                var totalPages = Math.ceil(totalResult / pageSize);

                // 如果只有一页，直接返回
                if (totalPages <= 1) return;

                // 递归请求剩余页面
                var promises = [];
                for (var p = 2; p <= totalPages; p++) {
                    promises.push(fetchPage(p, allItems, totalResult));
                }
                return Promise.all(promises);
            }
        });
    }

    /**
     * 主入口：获取成绩数据（支持多页合并）
     */
    function fetchScores() {
        console.log("成绩抓取脚本开始执行...");

        var allItems = [];

        fetchPage(1, allItems)
        .then(function () {
            if (allItems.length === 0) {
                AndroidBridge.showToast("未获取到成绩数据");
                return;
            }

            // 先弹数量，确认是数据问题还是显示截断
            alert("API 返回原始记录数: " + allItems.length);

            var results = allItems.map(extractItem);
            var output = JSON.stringify(results, null, 2);

            console.log("成功抓取 " + results.length + " 条成绩（合并 " + allItems.length + " 条原始记录）");
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
