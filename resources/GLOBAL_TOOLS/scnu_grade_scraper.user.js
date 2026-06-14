// ==UserScript==
// @name         华师（正方）成绩抓取调试工具
// @namespace    http://tampermonkey.net/
// @version      1.1
// @description  在华南师范大学正方教务系统成绩页面添加"抓取成绩"按钮，以表格展示抓取结果
// @author       You
// @match        https://jwxt.scnu.edu.cn/cjcx/cjcx_cxDgXscj.html*
// @icon         https://www.scnu.edu.cn/favicon.ico
// @grant        none
// ==/UserScript==

(function () {
    "use strict";

    var REQUEST_URL = "/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005";
    var PAGE_SIZE = 10;
    var MAX_PAGES = 20;

    /* ---------- 参数收集 ---------- */

    function getQueryParams(offset) {
        var form = document.getElementById("searchForm");
        if (!form) throw new Error("未找到 searchForm");

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

    /* ---------- 请求 ---------- */

    function fetchOffset(offset) {
        var params = getQueryParams(offset);

        return fetch(REQUEST_URL, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Requested-With": "XMLHttpRequest"
            },
            body: params.toString(),
            credentials: "include"
        }).then(function (r) {
            if (!r.ok) throw new Error("HTTP " + r.status);
            return r.json();
        }).then(function (data) {
            if (data.items && Array.isArray(data.items)) return data.items;
            if (data.rows && Array.isArray(data.rows)) return data.rows;
            if (Array.isArray(data)) return data;
            return [];
        });
    }

    /* ---------- 字段提取 ---------- */

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

    /* ---------- 主流程 ---------- */

    function fetchAllScores() {
        var allRawData = [];
        var offset = 0;
        var pageCount = 0;
        var statusEl = document.getElementById("gm_status");
        var tableBody = document.getElementById("gm_body");
        var countEl = document.getElementById("gm_count");

        statusEl.textContent = "抓取中...";

        function next() {
            if (pageCount >= MAX_PAGES) {
                statusEl.textContent = "已达最大页数 " + MAX_PAGES + "，停止";
                return Promise.resolve();
            }
            return fetchOffset(offset).then(function (items) {
                if (!items || items.length === 0) {
                    statusEl.textContent = "offset=" + offset + " 无数据，结束";
                    return;
                }
                pageCount++;

                var seen = {};
                for (var i = 0; i < allRawData.length; i++) {
                    var k = allRawData[i].kcmc || allRawData[i].bh || "";
                    seen[k] = true;
                }
                var added = 0;
                for (var j = 0; j < items.length; j++) {
                    var key = items[j].kcmc || items[j].bh || "";
                    if (!seen[key]) {
                        seen[key] = true;
                        allRawData.push(items[j]);
                        added++;
                    }
                }
                statusEl.textContent = "第 " + pageCount + " 页 offset=" + offset +
                    " 返回 " + items.length + " 条，新增 " + added + " 条";

                if (added === 0) {
                    statusEl.textContent = "数据已全部获取完毕！共 " + allRawData.length + " 条";
                    return;
                }
                offset += PAGE_SIZE;
                return next();
            });
        }

        return next().then(function () {
            var results = allRawData.map(extractItem);
            countEl.textContent = results.length;
            renderTable(tableBody, results);
            statusEl.textContent = "抓取完成！共 " + results.length + " 条";
            return results;
        }).catch(function (err) {
            statusEl.textContent = "失败: " + err.message;
        });
    }

    /* ---------- 渲染表格 ---------- */

    function renderTable(tbody, data) {
        tbody.innerHTML = "";
        if (data.length === 0) {
            tbody.innerHTML = "<tr><td colspan='4' style='text-align:center;color:#999;'>无数据</td></tr>";
            return;
        }
        for (var i = 0; i < data.length; i++) {
            var tr = document.createElement("tr");
            tr.innerHTML =
                "<td>" + (i + 1) + "</td>" +
                "<td>" + escapeHtml(data[i].courseName) + "</td>" +
                "<td>" + escapeHtml(data[i].credit) + "</td>" +
                "<td>" + escapeHtml(data[i].score) + "</td>" +
                "<td>" + escapeHtml(data[i].gpa) + "</td>";
            tbody.appendChild(tr);
        }
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    /* ---------- 界面注入 ---------- */

    function injectUI() {
        // 创建结果面板容器
        var panel = document.createElement("div");
        panel.id = "gm_panel";
        panel.style.cssText =
            "position:fixed;top:60px;right:20px;width:700px;max-height:80vh;" +
            "background:#fff;border:1px solid #ccc;border-radius:8px;" +
            "box-shadow:0 4px 20px rgba(0,0,0,0.3);z-index:9999;" +
            "display:flex;flex-direction:column;font:14px/1.5 'Microsoft YaHei',sans-serif;";

        panel.innerHTML =
            '<div style="padding:10px 15px;background:#0770cd;color:#fff;' +
            'border-radius:8px 8px 0 0;display:flex;justify-content:space-between;align-items:center;">' +
            '<span style="font-weight:bold;">成绩抓取结果 (<span id="gm_count">0</span> 条)</span>' +
            '<button id="gm_close" style="background:none;border:none;color:#fff;' +
            'font-size:20px;cursor:pointer;">×</button>' +
            '</div>' +
            '<div style="padding:8px 15px;background:#f5f5f5;border-bottom:1px solid #eee;' +
            'font-size:13px;color:#666;" id="gm_status">就绪</div>' +
            '<div style="overflow:auto;flex:1;">' +
            '<table style="width:100%;border-collapse:collapse;">' +
            '<thead><tr style="background:#f0f0f0;">' +
            '<th style="padding:6px 10px;text-align:left;border-bottom:1px solid #ddd;width:40px;">#</th>' +
            '<th style="padding:6px 10px;text-align:left;border-bottom:1px solid #ddd;">课程名称</th>' +
            '<th style="padding:6px 10px;text-align:left;border-bottom:1px solid #ddd;width:60px;">学分</th>' +
            '<th style="padding:6px 10px;text-align:left;border-bottom:1px solid #ddd;width:70px;">成绩</th>' +
            '<th style="padding:6px 10px;text-align:left;border-bottom:1px solid #ddd;width:70px;">绩点</th>' +
            '</tr></thead>' +
            '<tbody id="gm_body"></tbody>' +
            '</table>' +
            '</div>';

        document.body.appendChild(panel);

        // 关闭按钮
        document.getElementById("gm_close").onclick = function () {
            panel.style.display = panel.style.display === "none" ? "" : "none";
        };

        // 按钮注入
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-primary btn-sm";
        btn.style.cssText = "margin-left:10px;";
        btn.textContent = "抓取成绩";
        btn.onclick = function () {
            panel.style.display = "";
            btn.disabled = true;
            btn.textContent = "抓取中...";
            fetchAllScores().finally(function () {
                btn.disabled = false;
                btn.textContent = "抓取成绩";
            });
        };

        var searchBtn = document.getElementById("search_go");
        if (searchBtn && searchBtn.parentNode) {
            searchBtn.parentNode.appendChild(btn);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", injectUI);
    } else {
        injectUI();
    }
})();
