"""Render an evaluation report as Markdown tables for the thesis (no numbers are typed by hand).

Usage:
    python scripts/render_results_table.py \
        --report ../evidence/recommendation/synthetic-v1-test-final-20261117T082418Z.json \
        --out ../docs/recommendation-results.md
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

LABELS = {"random": "Random", "popularity": "Popularity", "association": "Luật kết hợp (thuần)",
          "association+popularity": "Luật kết hợp → popularity", "content-complement": "Content (complement)",
          "hybrid-fbt": "**Hybrid** luật → content → popularity", "content-profile": "Content (hồ sơ user)",
          "hybrid-home": "**Hybrid** hồ sơ → popularity"}
SEGMENTS = {"all": "Tất cả", "warm": "Warm", "cold": "Cold"}
VERDICTS = {"left better": "Trái tốt hơn", "right better": "Phải tốt hơn",
            "no significant difference": "Chưa có khác biệt có ý nghĩa", "not enough cases (< 30)": "Không đủ ca"}


def number(value: float) -> str:
    return f"{value:.3f}".replace(".", ",")


def metric_table(report: dict, task: str, segment: str) -> list[str]:
    lines = ["| Cấu hình | P@5 | R@5 | HR@5 | NDCG@5 (CI 95%) | P@10 | R@10 | HR@10 | NDCG@10 | Coverage@5 |",
             "|---|---|---|---|---|---|---|---|---|---|"]
    for result in report["results"]:
        rows = result["tasks"].get(task, {}).get(segment)
        if not rows or not rows["n"]:
            continue
        ci = rows["ndcg@5"]["ci95"]
        cells = [number(rows[f"{m}@5"]["mean"]) for m in ("precision", "recall", "hit_rate")]
        cells.append(f"{number(rows['ndcg@5']['mean'])} ({number(ci[0])}–{number(ci[1])})")
        cells += [number(rows[f"{m}@10"]["mean"]) for m in ("precision", "recall", "hit_rate", "ndcg")]
        cells.append(number(rows["coverage@5"]))
        lines.append(f"| {LABELS.get(result['name'], result['name'])} | " + " | ".join(cells) + " |")
    return lines


def render(report: dict, report_name: str, report_sha: str) -> str:
    fbt_n = {s: report["results"][0]["tasks"]["fbt"][s]["n"] for s in SEGMENTS}
    home = next(r for r in report["results"] if "home" in r["tasks"])["tasks"]["home"]
    out = [
        "# Kết quả đánh giá offline của Recommendation",
        "",
        "> **Tự sinh** bởi `recommendation-service/scripts/render_results_table.py`, không sửa tay.",
        f"> Nguồn: `evidence/recommendation/{report_name}` (SHA-256 `{report_sha[:16]}…`),"
        f" split `{report['split'].upper()}`, dữ liệu `{report['dataset']}` (synthetic), commit `{report['gitCommit'][:8]}`.",
        f"> Huấn luyện trên dữ liệu trước `{report['window']['trainUntil']}`, đánh giá"
        f" `{report['window']['evalFrom']}` → `{report['window']['evalUntil']}`,"
        f" {report['inputs']['candidates']['count']} sản phẩm ứng viên.",
        "> Protocol: `docs/recommendation-design.md` mục 7; cấu hình chọn trên DEV, TEST chạy một lần.",
        "",
        "Ký hiệu: P = Precision, R = Recall, HR = Hit Rate; NDCG gain tuyến tính, chia `log2(i + 1)`."
        " CI 95% là bootstrap 1.000 lần theo đơn hàng (FBT) hoặc user (HOME).",
        "",
        "## 1. Thường mua cùng (FREQUENTLY_BOUGHT_TOGETHER)",
        "",
        f"Mỗi sản phẩm của một đơn có ≥ 2 sản phẩm trong tuần đánh giá làm nguồn một lần; đáp án là các sản phẩm"
        f" còn lại của đơn. {fbt_n['all']} ca: {fbt_n['warm']} warm (nguồn đã có trong giỏ train),"
        f" {fbt_n['cold']} cold (sản phẩm mới).",
    ]
    for segment, label in SEGMENTS.items():
        out += ["", f"### 1.{list(SEGMENTS).index(segment) + 1}. {label} ({fbt_n[segment]} ca)", ""]
        out += metric_table(report, "fbt", segment)
    out += ["", "## 2. Gợi ý cho bạn (HOME)", "",
            f"Đáp án: sản phẩm user thêm giỏ (1) hoặc mua (2) trong tuần đánh giá. {home['all']['n']} user:"
            f" {home['warm']['n']} warm, {home['cold']['n']} cold (chưa có lịch sử)."]
    for segment, label in SEGMENTS.items():
        out += ["", f"### 2.{list(SEGMENTS).index(segment) + 1}. {label} ({home[segment]['n']} user)", ""]
        out += metric_table(report, "home", segment)
    out += ["", "## 3. So sánh đăng ký trước (NDCG@5, paired bootstrap)", "",
            "| Mã | Câu hỏi | Tác vụ / nhóm | Trái − Phải | Hiệu | CI 95% | Kết luận |", "|---|---|---|---|---|---|---|"]
    for c in report["selection"]["comparisons"]:
        diff = number(c["meanDifference"]) if "meanDifference" in c else "—"
        ci = f"{number(c['ci95'][0])} – {number(c['ci95'][1])}" if "ci95" in c else "—"
        pair = f"{LABELS.get(c['left'], c['left'])} − {LABELS.get(c['right'], c['right'])}".replace("**", "")
        out.append(f"| {c['id']} | {c['researchQuestion']} | {c['task'].upper()} / {SEGMENTS[c['segment']].lower()}"
                   f" (n={c['n']}) | {pair} | {diff} | {ci} | {VERDICTS.get(c['verdict'], c['verdict'])} |")
    out += ["", "## 4. Giới hạn", "",
            "- Dữ liệu hành vi là synthetic; bộ sinh và content-based cùng dựa trên thuộc tính sản phẩm.",
            "- Catalog nhỏ (36 sản phẩm), nhóm cold chỉ 39 ca: khoảng tin cậy rộng.",
            "- 9 so sánh không hiệu chỉnh đa so sánh.",
            "- Đánh giá offline, chưa có A/B test online.", ""]
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    data = args.report.read_bytes()
    args.out.write_text(render(json.loads(data), args.report.name, hashlib.sha256(data).hexdigest()),
                        encoding="utf-8")
    print(f"-> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
