// ==UserScript==
// @name         华师（正方）成绩抓取调试工具
// @namespace    http://tampermonkey.net/
// @version      1.0
// @description  在华南师范大学正方教务系统成绩页面添加"抓取成绩"按钮，输出 JSON 到控制台
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
        console.log("[抓取] offset=" + offset);

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
        console.log("[抓取] 开始...");

        var allRawData = [];
        var offset = 0;
        var pageCount = 0;

        function next() {
            if (pageCount >= MAX_PAGES) {
                console.warn("[抓取] 已达最大页数");
                return Promise.resolve();
            }
            return fetchOffset(offset).then(function (items) {
                if (!items || items.length === 0) {
                    console.log("[抓取] offset=" + offset + " 无数据，结束");
                    return;
                }
                pageCount++;

                // 去重
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
                console.log("[抓取] offset=" + offset + " → " + items.length +
                            " 条，新增 " + added + " 条");

                if (added === 0) {
                    console.log("[抓取] 全是重复，完毕");
                    return;
                }
                offset += PAGE_SIZE;
                return next();
            });
        }

        return next().then(function () {
            var results = allRawData.map(extractItem);
            console.log("[抓取] 共 " + results.length + " 条");
            console.table(results);
            alert("共抓取到 " + results.length + " 条成绩，请在控制台查看 JSON");
            return results;
        }).catch(function (err) {
            console.error("[抓取] 失败:", err);
            alert("抓取失败: " + err.message);
        });
    }

    /* ---------- 界面注入 ---------- */

    function injectButton() {
        var btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn btn-primary btn-sm";
        btn.style.cssText = "margin-left: 10px;";
        btn.textContent = "抓取成绩";
        btn.onclick = function () {
            btn.disabled = true;
            btn.textContent = "抓取中...";
            fetchAllScores().finally(function () {
                btn.disabled = false;
                btn.textContent = "抓取成绩";
            });
        };

        // 放在查询按钮旁边
        var searchBtn = document.getElementById("search_go");
        if (searchBtn && searchBtn.parentNode) {
            searchBtn.parentNode.appendChild(btn);
        } else {
            var container = document.querySelector(".container") || document.body;
            container.insertBefore(btn, container.firstChild);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", injectButton);
    } else {
        injectButton();
    }
})();
