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

"""Convert a Trivy or OWASP dependency-check JSON report into a numbered
Markdown table.

Usage:
    scan_report_md.py trivy  target/trivy.json                    trivy.md
    scan_report_md.py owasp  target/dependency-check-report.json  owasp.md

Only the Python standard library is used, so it runs anywhere python3 exists.
Invoked by the optional Maven profiles -Ptrivy / -Powasp (see pom.xml).
"""
import datetime
import json
import sys

SEV_ORDER = {
    "CRITICAL": 0,
    "HIGH": 1,
    "MEDIUM": 2,
    "MODERATE": 2,
    "LOW": 3,
    "INFO": 4,
    "INFORMATIONAL": 4,
    "UNKNOWN": 5,
    "": 5,
}


def esc(value):
    if value is None:
        return ""
    return str(value).replace("|", "\\|").replace("\n", " ").replace("\r", " ").strip()


def trunc(value, limit=180):
    text = esc(value)
    return text if len(text) <= limit else text[: limit - 1] + "…"


def load(path):
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


def collect_trivy(data):
    rows = []
    for result in data.get("Results", []) or []:
        target = result.get("Target", "")
        for vuln in result.get("Vulnerabilities", []) or []:
            rows.append(
                {
                    "cve": vuln.get("VulnerabilityID", ""),
                    "sev": (vuln.get("Severity") or "UNKNOWN").upper(),
                    "pkg": vuln.get("PkgName", ""),
                    "ver": vuln.get("InstalledVersion", ""),
                    "fixed": vuln.get("FixedVersion") or "—",
                    "desc": vuln.get("Title") or vuln.get("Description") or "",
                    "url": vuln.get("PrimaryURL") or "",
                }
            )
    return rows


def _cvss_score(vuln):
    for key in ("cvssv4", "cvssv3", "cvssv2"):
        block = vuln.get(key) or {}
        score = block.get("baseScore")
        if score is None:
            score = (block.get("cvssData") or {}).get("baseScore")
        if score is not None:
            return str(score)
    return ""


def collect_owasp(data):
    rows = []
    for dep in data.get("dependencies", []) or []:
        name = dep.get("fileName", "")
        for vuln in dep.get("vulnerabilities", []) or []:
            refs = vuln.get("references") or []
            url = refs[0].get("url", "") if refs and isinstance(refs[0], dict) else ""
            rows.append(
                {
                    "cve": vuln.get("name", ""),
                    "sev": (vuln.get("severity") or "UNKNOWN").upper(),
                    "pkg": name,
                    "score": _cvss_score(vuln),
                    "desc": vuln.get("description", ""),
                    "url": vuln.get("source", "") and url or url,
                }
            )
    return rows


def summary(rows):
    counts = {}
    for row in rows:
        counts[row["sev"]] = counts.get(row["sev"], 0) + 1
    order = sorted(counts, key=lambda s: SEV_ORDER.get(s, 9))
    return ", ".join(f"{sev}: {counts[sev]}" for sev in order) or "none"


def render_trivy(rows, source):
    rows.sort(key=lambda r: (SEV_ORDER.get(r["sev"], 9), r["cve"]))
    out = []
    out.append("# Trivy — Dependency Vulnerabilities\n")
    out.append(f"- **Generated:** {datetime.datetime.now().isoformat(timespec='seconds')}")
    out.append(f"- **Source:** `{source}` (scanned fat JAR, including nested dependencies)")
    out.append(f"- **Found:** {len(rows)} ({summary(rows)})\n")
    if not rows:
        out.append("No vulnerabilities found.\n")
        return "\n".join(out)
    out.append("| # | CVE | Severity | Package | Version | Fixed in | Description |")
    out.append("|---|-----|----------|-------|--------|--------------|----------|")
    for i, r in enumerate(rows, 1):
        cve = f"[{esc(r['cve'])}]({r['url']})" if r["url"] else esc(r["cve"])
        out.append(
            f"| {i} | {cve} | {esc(r['sev'])} | {esc(r['pkg'])} | {esc(r['ver'])} | "
            f"{esc(r['fixed'])} | {trunc(r['desc'])} |"
        )
    out.append("")
    return "\n".join(out)


def render_owasp(rows, source):
    rows.sort(key=lambda r: (SEV_ORDER.get(r["sev"], 9), r["cve"]))
    out = []
    out.append("# OWASP dependency-check — Dependency Vulnerabilities\n")
    out.append(f"- **Generated:** {datetime.datetime.now().isoformat(timespec='seconds')}")
    out.append(f"- **Source:** `{source}` (cross-check against the NVD database via the NVD API)")
    out.append(f"- **Found:** {len(rows)} ({summary(rows)})\n")
    if not rows:
        out.append("No vulnerabilities found.\n")
        return "\n".join(out)
    out.append("| # | CVE | Severity | CVSS | Component (artifact) | Description |")
    out.append("|---|-----|----------|------|----------------------|----------|")
    for i, r in enumerate(rows, 1):
        cve = f"[{esc(r['cve'])}]({r['url']})" if r["url"] else esc(r["cve"])
        out.append(
            f"| {i} | {cve} | {esc(r['sev'])} | {esc(r.get('score', ''))} | "
            f"{esc(r['pkg'])} | {trunc(r['desc'])} |"
        )
    out.append("")
    return "\n".join(out)


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    mode, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
    data = load(src)
    if mode == "trivy":
        markdown = render_trivy(collect_trivy(data), src)
    elif mode == "owasp":
        markdown = render_owasp(collect_owasp(data), src)
    else:
        print(f"unknown mode: {mode}", file=sys.stderr)
        sys.exit(2)
    with open(dst, "w", encoding="utf-8") as handle:
        handle.write(markdown)
    print(f"[{mode}] wrote {dst}")


if __name__ == "__main__":
    main()
