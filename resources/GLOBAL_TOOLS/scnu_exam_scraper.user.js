// ==UserScript==
// @name         华师（正方）考试信息抓取工具
// @namespace    http://tampermonkey.net/
// @version      1.0
// @description  在华南师范大学正方教务系统考试安排页面抓取考试信息，以表格展示
// @author       You
// @match        https://jwxt.scnu.edu.cn/kwgl/kscx_cxXsksxxIndex.html*
// @icon         https://www.scnu.edu.cn/favicon.ico
// @grant        none
// ==/UserScript==

(function () {
    "use strict";

    var REQUEST_URL = "/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105";

    // 各学期对应的 xqm 值
    var SEMESTERS = [
        { value: "3", label: "第1学期" },
        { value: "12", label: "第2学期" },
        { value: "16", label: "第3学期" }
    ];

    function getQueryParams(xqm) {
        var params = new URLSearchParams();

        var xnmEl = document.getElementById("xnm");
        params.set("xnm", xnmEl ? xnmEl.value : "");
        params.set("xqm", xqm);
        params.set("ksmcdmb_id", "");
        params.set("kch", "");
        params.set("kc", "");
        params.set("ksrq", "");
        params.set("kkbm_id", "");

        params.set("_search", "false");
        params.set("nd", String(Date.now()));
        params.set("queryModel.showCount", "15");
        params.set("queryModel.currentPage", "1");
        params.set("queryModel.sortName", " ");
        params.set("queryModel.sortOrder", "asc");
        params.set("time", "1");

        return params;
    }

    function fetchExams(xqm) {
        var params = getQueryParams(xqm);
        console.log("[抓取考试] xqm=" + xqm + " 参数:", params.toString());

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
            var items = data.items && Array.isArray(data.items) ? data.items
                : data.rows && Array.isArray(data.rows) ? data.rows
                : Array.isArray(data) ? data
                : [];
            console.log("[抓取考试] 返回", items.length, "条, totalResult:", data.totalResult);
            return items;
        });
    }

    function extractItem(raw) {
        if (raw.cell && Array.isArray(raw.cell)) {
            return {
                courseName: raw.cell[0] ? String(raw.cell[0]).trim() : "",
                examDate: raw.cell[1] ? String(raw.cell[1]).trim() : "",
                location: raw.cell[2] ? String(raw.cell[2]).trim() : "",
                examType: raw.cell[3] ? String(raw.cell[3]).trim() : ""
            };
        }
        return {
            courseName: (raw.kcmc || "").trim(),
            examDate: (raw.kssj || "").trim(),
            location: (raw.cdmc || "") + " " + (raw.cdxqmc || "").trim(),
            examType: (raw.khfs || raw.ksfs || "").trim(),
            teacher: (raw.jsxx || "").trim(),
            credit: (raw.xf || "").toString().trim(),
            examName: (raw.ksmc || "").trim()
        };
    }

    /* ---------- 渲染表格 ---------- */

    function renderTable(tbody, data) {
        tbody.innerHTML = "";
        if (data.length === 0) {
            tbody.innerHTML = "<tr><td colspan='8' style='text-align:center;color:#999;padding:20px;'>无数据</td></tr>";
            return;
        }
        // 按学期、考试日期排序
        data.sort(function (a, b) {
            var sA = a.semester || "", sB = b.semester || "";
            if (sA !== sB) return sA.localeCompare(sB);
            return a.examDate.localeCompare(b.examDate);
        });
        for (var i = 0; i < data.length; i++) {
            var tr = document.createElement("tr");
            var dateHtml = data[i].examDate.replace(/\(/, "<br>(");
            tr.innerHTML =
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;width:45px;'>" + (i + 1) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;width:45px;'>" + esc(data[i].semester || "") + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(data[i].courseName) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + dateHtml + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(data[i].location) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(data[i].examType) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(data[i].credit) + "</td>" +
                "<td style='padding:6px 10px;border-bottom:1px solid #eee;'>" + esc(data[i].teacher) + "</td>";
            tbody.appendChild(tr);
        }
    }

    function esc(str) {
        return String(str).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    /* ---------- 界面注入 ---------- */

    function injectUI() {
        var panel = document.createElement("div");
        panel.id = "gm_panel";
        panel.style.cssText =
            "position:fixed;top:60px;right:20px;width:850px;max-height:85vh;" +
            "background:#fff;border:1px solid #ccc;border-radius:8px;" +
            "box-shadow:0 4px 20px rgba(0,0,0,0.3);z-index:9999;" +
            "display:flex;flex-direction:column;font:14px/1.5 'Microsoft YaHei',sans-serif;";

        panel.innerHTML =
            '<div style="padding:10px 15px;background:#0770cd;color:#fff;border-radius:8px 8px 0 0;' +
            'display:flex;justify-content:space-between;align-items:center;">' +
            '<span style="font-weight:bold;">考试安排 (<span id="gm_count">0</span> 场)</span>' +
            '<span>' +
            '<button id="gm_copy" style="background:rgba(255,255,255,0.2);border:1px solid rgba(255,255,255,0.5);' +
            'color:#fff;border-radius:4px;padding:2px 10px;cursor:pointer;margin-right:8px;">复制 JSON</button>' +
            '<button id="gm_close" style="background:none;border:none;color:#fff;font-size:20px;cursor:pointer;">×</button>' +
            '</span></div>' +
            '<div style="padding:8px 15px;background:#f5f5f5;border-bottom:1px solid #eee;font-size:13px;color:#666;" id="gm_status">就绪</div>' +
            '<div style="overflow:auto;flex:1;">' +
            '<table style="width:100%;border-collapse:collapse;font-size:13px;">' +
            '<thead><tr style="background:#f0f0f0;">' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:45px;">学期</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;">课程</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:130px;">考试时间</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:120px;">地点</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:55px;">形式</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;width:40px;">学分</th>' +
            '<th style="padding:6px 8px;text-align:left;border-bottom:1px solid #ddd;">教师</th>' +
            '</tr></thead>' +
            '<tbody id="gm_body"></tbody>' +
            '</table></div>';

        document.body.appendChild(panel);

        document.getElementById("gm_close").onclick = function () {
            panel.style.display = panel.style.display === "none" ? "" : "none";
        };

        document.getElementById("gm_copy").onclick = function () {
            var body = document.getElementById("gm_body");
            var rows = body.querySelectorAll("tr");
            var data = [];
            for (var i = 0; i < rows.length; i++) {
                var cells = rows[i].querySelectorAll("td");
                if (cells.length >= 8) {
                    data.push({
                        semester: cells[1].textContent,
                        courseName: cells[2].textContent,
                        examDate: cells[3].innerHTML.replace(/<br>.*/, ""),
                        location: cells[4].textContent,
                        examType: cells[5].textContent,
                        credit: cells[6].textContent,
                        teacher: cells[7].textContent
                    });
                }
            }
            navigator.clipboard.writeText(JSON.stringify(data, null, 2)).then(function () {
                alert("已复制 " + data.length + " 条 JSON 到剪贴板");
            });
        };

        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-primary btn-sm";
        btn.style.cssText = "margin-left:10px;";
        btn.textContent = "抓取考试";
        btn.onclick = function () {
            panel.style.display = "";
            btn.disabled = true;
            btn.textContent = "抓取中...";

            var statusEl = document.getElementById("gm_status");
            var tableBody = document.getElementById("gm_body");
            var countEl = document.getElementById("gm_count");
            countEl.textContent = "0";
            tableBody.innerHTML = "";

            // 串行请求每个学期
            var allResults = [];
            var semesterIndex = 0;

            function nextSemester() {
                if (semesterIndex >= SEMESTERS.length) {
                    // 全部完成
                    renderTable(tableBody, allResults);
                    countEl.textContent = allResults.length;
                    statusEl.textContent = "抓取完成！共 " + allResults.length + " 场考试";
                    btn.disabled = false;
                    btn.textContent = "抓取考试";
                    return;
                }

                var sem = SEMESTERS[semesterIndex];
                statusEl.textContent = "正在抓取 " + sem.label + "...";

                fetchExams(sem.value).then(function (items) {
                    if (items && items.length > 0) {
                        var results = items.map(extractItem);
                        for (var i = 0; i < results.length; i++) {
                            results[i].semester = sem.label;
                        }
                        allResults.push.apply(allResults, results);
                        statusEl.textContent = sem.label + " 获取到 " + results.length + " 场";
                    } else {
                        statusEl.textContent = sem.label + " 无考试";
                    }
                    semesterIndex++;
                    nextSemester();
                }).catch(function (err) {
                    statusEl.textContent = sem.label + " 失败: " + err.message;
                    semesterIndex++;
                    nextSemester();
                });
            }

            nextSemester();
        };

        var searchBtn = document.getElementById("search_go");
        if (searchBtn && searchBtn.parentNode) {
            searchBtn.parentNode.appendChild(btn);
        } else {
            var container = document.querySelector(".container") || document.body;
            container.insertBefore(btn, container.firstChild);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", injectUI);
    } else {
        injectUI();
    }
})();
