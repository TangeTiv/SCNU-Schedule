/**
 * 华南师范大学成绩抓取脚本 - 自动翻页版
 *
 * 运行环境：Android WebView（已登录状态，Session/Cookie 自动携带）
 * 功能：从第 1 页开始请求，当某页返回 0 条数据时停止，
 *       或达到最大页数限制时停止（防死循环）。
 *       自动合并所有记录，以 JSON 弹窗展示。
 */

(function () {
    "use strict";

    var REQUEST_URL = "/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005";
    var PAGE_SIZE = 100;
    var MAX_PAGES = 10;  // 安全上限，防止 page 参数被忽略时无限循环

    /**
     * 收集查询参数
     */
    function getQueryParams(page) {
        var form = document.getElementById("searchForm");
        if (!form) {
            throw new Error("未找到 searchForm，页面可能未完全加载");
        }

        var params = new URLSearchParams();
        var elements = form.elements;
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            if (el.name && el.name !== "" && el.name !== "autocomplete" && el.type !== "submit") {
                params.append(el.name, el.value);
            }
        }

        params.set("rows", String(PAGE_SIZE));
        params.set("page", String(page));
        return params;
    }

    /**
     * 请求某一页，返回该页的记录数组
     */
    function fetchPage(page) {
        var params = getQueryParams(page);

        return fetch(REQUEST_URL, {
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
            if (data.items && Array.isArray(data.items)) {
                return data.items;
            }
            if (data.rows && Array.isArray(data.rows)) {
                return data.rows;
            }
            if (Array.isArray(data)) {
                return data;
            }
            console.warn("未知数据格式:", data);
            return [];
        });
    }

    /**
     * 从原始记录中提取标准字段
     */
    function extractItem(raw) {
        // jqGrid 格式 { cell: [...] }
        if (raw.cell && Array.isArray(raw.cell)) {
            return {
                courseName: raw.cell[0] ? String(raw.cell[0]).trim() : "",
                credit: raw.cell[1] ? String(raw.cell[1]).trim() : "",
                score: raw.cell[2] ? String(raw.cell[2]).trim() : "",
                gpa: raw.cell[3] ? String(raw.cell[3]).trim() : ""
            };
        }
        // 对象格式 { kcmc, xf, cj, jd }
        return {
            courseName: (raw.kcmc || raw.courseName || "").trim(),
            credit: (raw.xf || raw.credit || "").toString().trim(),
            score: (raw.cj || raw.score || "").toString().trim(),
            gpa: (raw.jd || raw.gpa || "").toString().trim()
        };
    }

    /**
     * 主入口：串行请求各页，直到取完或到达上限
     */
    function fetchAllScores() {
        console.log("成绩抓取脚本开始执行...");

        var allRawData = [];
        var currentPage = 1;

        function requestNextPage() {
            if (currentPage > MAX_PAGES) {
                console.warn("已达最大页数限制 " + MAX_PAGES + "，停止翻页");
                return Promise.resolve();
            }

            return fetchPage(currentPage).then(function (items) {
                if (!items || items.length === 0) {
                    console.log("第 " + currentPage + " 页无数据，抓取结束");
                    return Promise.resolve();
                }

                console.log("第 " + currentPage + " 页获取到 " + items.length + " 条");
                allRawData.push.apply(allRawData, items);
                currentPage++;
                return requestNextPage();
            });
        }

        requestNextPage()
            .then(function () {
                if (allRawData.length === 0) {
                    AndroidBridge.showToast("未获取到任何成绩数据");
                    alert("未获取到任何成绩数据，请确认页面已加载完成");
                    return;
                }

                var results = allRawData.map(extractItem);
                var output = JSON.stringify(results, null, 2);

                console.log("共抓取 " + results.length + " 条成绩（原始记录 " + allRawData.length + " 条）");
                AndroidBridge.showToast("共抓取到 " + results.length + " 条成绩数据");
                alert(output);
            })
            .catch(function (error) {
                console.error("请求失败:", error);
                AndroidBridge.showToast("获取成绩失败: " + error.message);
            });
    }

    fetchAllScores();
})();
