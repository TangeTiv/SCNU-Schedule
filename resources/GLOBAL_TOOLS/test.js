/**
 * 华南师范大学（正方教务系统）成绩抓取测试脚本
 *
 * 运行环境：Android WebView（已登录状态，Session/Cookie 自动携带）
 * 功能：严格按浏览器"查询"按钮的参数格式请求成绩 API，
 *       解析 JSON 并返回全部成绩列表。
 * 输出：alert 弹出 JSON，AndroidBridge.showToast 提示数量
 */

(function () {
    "use strict";

    var REQUEST_URL = "/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005";

    /**
     * 严格按浏览器"查询"按钮发送的参数格式构建请求
     */
    function getQueryParams() {
        var form = document.getElementById("searchForm");
        if (!form) {
            AndroidBridge.showToast("未找到 searchForm，页面可能未完全加载");
            return null;
        }

        var params = new URLSearchParams();

        // 序列化表单字段，跳过 Chosen 插件产生的 autocomplete 干扰项
        var elements = form.elements;
        for (var i = 0; i < elements.length; i++) {
            var el = elements[i];
            if (el.name && el.name !== "" && el.name !== "autocomplete" && el.type !== "submit") {
                params.append(el.name, el.value);
            }
        }

        // 浏览器"查询"按钮发送的额外参数（关键：queryModel.showCount 控制返回条数）
        params.set("sfzgcj", "");
        params.set("kcbj", "");
        params.set("_search", "false");
        params.set("nd", String(Date.now()));
        params.set("queryModel.showCount", "15");
        params.set("queryModel.currentPage", "1");
        params.set("queryModel.sortName", " ");
        params.set("queryModel.sortOrder", "asc");
        params.set("time", "0");

        return params;
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
     * 主入口：单次请求获取全部成绩数据
     */
    function fetchScores() {
        console.log("成绩抓取脚本开始执行...");

        var params = getQueryParams();
        if (!params) return;

        fetch(REQUEST_URL, {
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
            var rawItems;
            if (data.items && Array.isArray(data.items)) {
                rawItems = data.items;
            } else if (data.rows && Array.isArray(data.rows)) {
                rawItems = data.rows;
            } else if (Array.isArray(data)) {
                rawItems = data;
            } else {
                rawItems = [];
            }

            if (rawItems.length === 0) {
                AndroidBridge.showToast("未获取到成绩数据");
                alert("未获取到成绩数据，原始响应：\n" + JSON.stringify(data, null, 2).slice(0, 2000));
                return;
            }

            var results = rawItems.map(extractItem);
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
