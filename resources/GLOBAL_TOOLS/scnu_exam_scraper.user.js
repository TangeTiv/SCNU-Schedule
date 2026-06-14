// ==UserScript==
// @name         华师（正方）考试信息抓取工具
// @namespace    http://tampermonkey.net/
// @version      1.1
// @description  在华南师范大学正方教务系统考试安排页面抓取考试信息
// @author       You
// @match        https://jwxt.scnu.edu.cn/kwgl/kscx_cxXsksxxIndex.html*
// @icon         https://www.scnu.edu.cn/favicon.ico
// @grant        none
// ==/UserScript==

(function () {
    "use strict";

    var REQUEST_URL = "/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105";

    /* ---------- 参数 ---------- */

    function getCurrentXqm() {
        var el = document.querySelector('[name="xqm"]') || document.getElementById("xqm");
        return (el && el.value) || "12";
    }

    function getQueryParams() {
        var p = new URLSearchParams();
        var xnmEl = document.querySelector('[name="xnm"]');
        p.set("xnm", xnmEl ? xnmEl.value : "");
        p.set("xqm", getCurrentXqm());
        p.set("ksmcdmb_id", "");
        p.set("kch", "");
        p.set("kc", "");
        p.set("ksrq", "");
        p.set("kkbm_id", "");
        p.set("_search", "false");
        p.set("nd", String(Date.now()));
        p.set("queryModel.showCount", "15");
        p.set("queryModel.currentPage", "1");
        p.set("queryModel.sortName", " ");
        p.set("queryModel.sortOrder", "asc");
        p.set("time", "1");
        return p;
    }

    /* ---------- 请求 ---------- */

    function fetchExams() {
        return fetch(REQUEST_URL, {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Requested-With": "XMLHttpRequest"
            },
            body: getQueryParams().toString(),
            credentials: "include"
        }).then(function (r) {
            if (!r.ok) throw new Error("HTTP " + r.status);
            return r.json();
        }).then(function (data) {
            return data.items && Array.isArray(data.items) ? data.items
                : data.rows && Array.isArray(data.rows) ? data.rows
                : Array.isArray(data) ? data
                : [];
        });
    }

    /* ---------- 提取 ---------- */

    function extractItem(raw) {
        if (raw.cell && Array.isArray(raw.cell)) {
            return {
                courseName: String(raw.cell[0] || "").trim(),
                examDate: String(raw.cell[1] || "").trim(),
                location: String(raw.cell[2] || "").trim(),
                examType: String(raw.cell[3] || "").trim(),
                teacher: "", credit: ""
            };
        }
        return {
            courseName: (raw.kcmc || "").trim(),
            examDate: (raw.kssj || "").trim(),
            location: ((raw.cdmc || "") + " " + (raw.cdxqmc || "")).trim(),
            examType: (raw.khfs || raw.ksfs || "").trim(),
            teacher: (raw.jsxx || "").trim(),
            credit: (raw.xf || "").toString().trim()
        };
    }

    /* ---------- 渲染 ---------- */

    function renderTable(tbody, data) {
        tbody.innerHTML = "";
        if (!data || data.length === 0) {
            tbody.innerHTML = "<tr><td colspan='7' style='text-align:center;color:#999;padding:20px;'>无数据</td></tr>";
            return;
        }
        for (var i = 0; i < data.length; i++) {
            var d = data[i];
            var timeHtml = d.examDate.replace(/\(/, "<br>(");
            tbody.innerHTML +=
                "<tr>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + (i + 1) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(d.courseName) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + timeHtml + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(d.location) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(d.examType) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(d.credit) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(d.teacher) + "</td>" +
                "</tr>";
        }
    }

    function esc(s) { return String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;"); }

    /* ---------- 界面 ---------- */

    function injectUI() {
        var panel = document.createElement("div");
        panel.id = "gm_panel";
        panel.style.cssText = "position:fixed;top:60px;right:20px;width:850px;max-height:85vh;" +
            "background:#fff;border:1px solid #ccc;border-radius:8px;" +
            "box-shadow:0 4px 20px rgba(0,0,0,0.3);z-index:9999;" +
            "display:flex;flex-direction:column;font:14px/1.5 'Microsoft YaHei',sans-serif;";
        panel.innerHTML =
            '<div style="padding:10px 15px;background:#0770cd;color:#fff;border-radius:8px 8px 0 0;' +
            'display:flex;justify-content:space-between;align-items:center;">' +
            '<span style="font-weight:bold;">考试安排 (<span id="gm_count">0</span> 场)</span>' +
            '<span><button id="gm_copy" style="background:rgba(255,255,255,0.2);border:1px solid rgba(255,255,255,0.5);' +
            'color:#fff;border-radius:4px;padding:2px 10px;cursor:pointer;margin-right:8px;">复制 JSON</button>' +
            '<button id="gm_close" style="background:none;border:none;color:#fff;font-size:20px;cursor:pointer;">×</button></span></div>' +
            '<div style="padding:8px 15px;background:#f5f5f5;border-bottom:1px solid #eee;font-size:13px;color:#666;" id="gm_status">就绪</div>' +
            '<div style="overflow:auto;flex:1;">' +
            '<table style="width:100%;border-collapse:collapse;font-size:13px;">' +
            '<thead><tr style="background:#f0f0f0;">' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;">#</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;">课程</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:130px;">考试时间</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:120px;">地点</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:55px;">形式</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:40px;">学分</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;">教师</th></tr></thead>' +
            '<tbody id="gm_body"></tbody></table></div>';
        document.body.appendChild(panel);

        document.getElementById("gm_close").onclick = function () {
            panel.style.display = panel.style.display === "none" ? "" : "none";
        };
        document.getElementById("gm_copy").onclick = function () {
            var rows = document.querySelectorAll("#gm_body tr");
            var data = [];
            for (var i = 0; i < rows.length; i++) {
                var c = rows[i].querySelectorAll("td");
                if (c.length >= 7) data.push({
                    courseName: c[1].textContent,
                    examDate: c[2].innerHTML.replace(/<br>.*/, ""),
                    location: c[3].textContent,
                    examType: c[4].textContent,
                    credit: c[5].textContent,
                    teacher: c[6].textContent
                });
            }
            navigator.clipboard.writeText(JSON.stringify(data, null, 2));
        };

        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-primary btn-sm";
        btn.style.cssText = "margin-left:10px;";
        btn.textContent = "抓取考试";
        btn.onclick = function () {
            panel.style.display = "";
            btn.disabled = true;
            btn.textContent = "请求中...";

            var statusEl = document.getElementById("gm_status");
            var bodyEl = document.getElementById("gm_body");
            var countEl = document.getElementById("gm_count");

            fetchExams().then(function (items) {
                statusEl.textContent = "API 返回 " + items.length + " 条，正在过滤...";

                // 按学期值过滤
                var target = getCurrentXqm();
                var filtered = items.filter(function (v) { return String(v.xqm) === target; });

                // 如果过滤完是 0 但原始数据有，显示原始数据（并提示）
                if (filtered.length === 0 && items.length > 0) {
                    filtered = items;
                    statusEl.textContent = "注意：学期过滤未命中，显示全部 " + items.length + " 条";
                } else {
                    statusEl.textContent = "共 " + filtered.length + " 场考试（第 " +
                        (target === "3" ? "1" : target === "12" ? "2" : "3") + " 学期）";
                }

                var results = filtered.map(extractItem);
                countEl.textContent = results.length;
                renderTable(bodyEl, results);
            }).catch(function (err) {
                statusEl.textContent = "失败: " + err.message;
            }).finally(function () {
                btn.disabled = false;
                btn.textContent = "抓取考试";
            });
        };

        var searchBtn = document.getElementById("search_go");
        if (searchBtn && searchBtn.parentNode) searchBtn.parentNode.appendChild(btn);
    }

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", injectUI);
    else injectUI();
})();
