/**
 * 华南师范大学成绩抓取脚本 - offset 偏移翻页版
 *
 * 运行环境：Android WebView（已登录状态，Session/Cookie 自动携带）
 * 功能：使用 offset 偏移参数翻页，逐页请求直到取完所有数据。
 *       避免 API 忽略 page 参数导致重复数据的问题。
 */

(function () {
    "use strict";

    var REQUEST_URL = "/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005";
    var PAGE_SIZE = 10;    // 和 API 实际返回条数保持一致
    var MAX_PAGES = 20;    // 安全上限

    /**
     * 收集查询参数
     */
    function getQueryParams(offset) {
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
        params.set("offset", String(offset));
        return params;
    }

    /**
     * 请求某一偏移位置的数据，返回该页的记录数组
     */
    function fetchOffset(offset) {
        var params = getQueryParams(offset);
        console.log("请求 offset=" + offset);

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
            return [];
        });
    }

    /**
     * 从原始记录中提取标准字段
     */
    function extractItem(raw) {
        if (raw.cell && Array.isArray(raw.cell)) {
            return {
                courseName: raw.cell[0] ? String(raw.cell[0]).trim() : "",
                credit: raw.cell[1] ? String(raw.cell[1]).trim() : "",
                score: raw.cell[2] ? String(raw.cell[2]).trim() : "",
                gpa: raw.cell[3] ? String(raw.cell[3]).trim() : ""
            };
        }
        return {
            courseName: (raw.kcmc || raw.courseName || "").trim(),
            credit: (raw.xf || raw.credit || "").toString().trim(),
            score: (raw.cj || raw.score || "").toString().trim(),
            gpa: (raw.jd || raw.gpa || "").toString().trim()
        };
    }

    /**
     * 主入口：串行请求各偏移位置，数据出现重复时自动停止
     */
    function fetchAllScores() {
        console.log("成绩抓取脚本开始执行...");

        var allRawData = [];
        var offset = 0;
        var pageCount = 0;

        function requestNext() {
            if (pageCount >= MAX_PAGES) {
                console.warn("已达最大页数限制 " + MAX_PAGES);
                return Promise.resolve();
            }

            return fetchOffset(offset).then(function (items) {
                if (!items || items.length === 0) {
                    console.log("offset=" + offset + " 无数据，抓取结束");
                    return Promise.resolve();
                }

                pageCount++;

                // 去重：和已有数据对比，如果全是重复则停止
                var existingKeys = {};
                for (var i = 0; i < allRawData.length; i++) {
                    var k = allRawData[i].kcmc || allRawData[i].bh || "";
                    existingKeys[k] = true;
                }
                var newCount = 0;
                for (var j = 0; j < items.length; j++) {
                    var key = items[j].kcmc || items[j].bh || "";
                    if (!existingKeys[key]) {
                        existingKeys[key] = true;
                        allRawData.push(items[j]);
                        newCount++;
                    }
                }

                console.log("offset=" + offset + " 返回 " + items.length +
                            " 条，新增 " + newCount + " 条");

                if (newCount === 0) {
                    // 全是重复，说明已经取完
                    console.log("数据已全部获取完毕");
                    return Promise.resolve();
                }

                offset += PAGE_SIZE;
                return requestNext();
            });
        }

        requestNext()
            .then(function () {
                if (allRawData.length === 0) {
                    AndroidBridge.showToast("未获取到任何成绩数据");
                    alert("未获取到任何成绩数据");
                    return;
                }

                var results = allRawData.map(extractItem);
                var output = JSON.stringify(results, null, 2);

                console.log("共抓取 " + results.length + " 条成绩");
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
