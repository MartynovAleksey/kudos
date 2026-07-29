#!/usr/bin/env python3
# Copyright 2026 Aleksey Martynov and contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Формирует автономный HTML-отчёт из XML-файлов Maven Surefire."""

from __future__ import annotations

import argparse
import html
from pathlib import Path
import xml.etree.ElementTree as ET


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", default="target/surefire-reports")
    parser.add_argument("--output", default="target/reports/autotest-report.html")
    args = parser.parse_args()

    suites = []
    for path in sorted(Path(args.input).glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        cases = []
        for case in root.findall("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            skipped = case.find("skipped")
            status = "Ошибка" if problem is not None else "Пропущен" if skipped is not None else "Успешно"
            details = "" if problem is None else (problem.get("message") or problem.text or "")
            cases.append((case.get("name", ""), case.get("time", "0"), status, details))
        suites.append((root.get("name", path.stem), cases))

    total = sum(len(cases) for _, cases in suites)
    failed = sum(status == "Ошибка" for _, cases in suites for _, _, status, _ in cases)
    skipped = sum(status == "Пропущен" for _, cases in suites for _, _, status, _ in cases)
    rows = []
    for suite, cases in suites:
        for name, duration, status, details in cases:
            css = {"Успешно": "ok", "Ошибка": "fail", "Пропущен": "skip"}[status]
            rows.append(
                f"<tr class='{css}'><td>{html.escape(suite)}</td><td>{html.escape(name)}</td>"
                f"<td>{html.escape(duration)} с</td><td>{status}</td>"
                f"<td><pre>{html.escape(details)}</pre></td></tr>"
            )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "<!doctype html><html lang='ru'><head><meta charset='utf-8'>"
        "<title>Отчёт автотестов KUDOS</title><style>"
        "body{font:14px system-ui;margin:32px;color:#172033}"
        "table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccd3df;padding:8px;text-align:left}"
        "th{background:#edf1f7}.ok td:nth-child(4){color:#08783e}.fail td:nth-child(4){color:#b42318}"
        ".skip td:nth-child(4){color:#8a5a00}pre{white-space:pre-wrap;margin:0}"
        "</style></head><body><h1>Отчёт автотестов KUDOS</h1>"
        f"<p>Всего: <b>{total}</b>; ошибок: <b>{failed}</b>; пропущено: <b>{skipped}</b>.</p>"
        "<table><thead><tr><th>Набор</th><th>Тест</th><th>Время</th><th>Результат</th><th>Подробности</th>"
        f"</tr></thead><tbody>{''.join(rows)}</tbody></table></body></html>",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
