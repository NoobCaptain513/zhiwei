#!/usr/bin/env python3
"""Run a reproducible live comparison of ZhiWei RAG retrieval strategies."""

import argparse
import datetime as dt
import hashlib
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EVAL_SET = ROOT / "evaluation" / "rag" / "eval-set-v1.json"
DEFAULT_CORPUS = ROOT / "evaluation" / "rag" / "corpus-v1.json"
STRATEGIES = ("HYBRID", "VECTOR_HEAVY", "KEYWORD_HEAVY")


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(corpus: dict, eval_set: dict) -> dict:
    documents = corpus.get("documents", [])
    cases = eval_set.get("queries", [])
    source_ids = [doc.get("sourceId") for doc in documents]
    case_ids = [case.get("caseId") for case in cases]
    errors = []
    if not 100 <= len(cases) <= 300:
        errors.append(f"evaluation set must contain 100-300 cases, got {len(cases)}")
    if len(source_ids) != len(set(source_ids)) or any(not item for item in source_ids):
        errors.append("corpus sourceId values must be non-empty and unique")
    if len(case_ids) != len(set(case_ids)) or any(not item for item in case_ids):
        errors.append("caseId values must be non-empty and unique")
    known_sources = set(source_ids)
    multi_document = 0
    for case in cases:
        judgments = case.get("relevantDocuments") or []
        if len(judgments) > 1:
            multi_document += 1
        judged_sources = [item.get("sourceId") for item in judgments]
        if not case.get("query", "").strip():
            errors.append(f"{case.get('caseId')}: blank query")
        if not judged_sources or len(judged_sources) != len(set(judged_sources)):
            errors.append(f"{case.get('caseId')}: judgments must be non-empty and unique")
        for judgment in judgments:
            if judgment.get("sourceId") not in known_sources:
                errors.append(f"{case.get('caseId')}: unknown source {judgment.get('sourceId')}")
            if judgment.get("relevance") not in (1, 2, 3):
                errors.append(f"{case.get('caseId')}: invalid relevance {judgment.get('relevance')}")
    if errors:
        raise ValueError("\n".join(errors))
    return {
        "documents": len(documents),
        "queries": len(cases),
        "multiDocumentQueries": multi_document,
        "judgments": sum(len(case["relevantDocuments"]) for case in cases),
    }


def post_json(base_url: str, path: str, payload: dict, timeout: int) -> dict:
    last_error = None
    for attempt in range(1, 5):
        request = urllib.request.Request(
            base_url.rstrip("/") + path,
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = json.loads(response.read().decode("utf-8"))
            if body.get("success"):
                return body["data"]
            last_error = RuntimeError(f"POST {path} failed: {body}")
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            last_error = RuntimeError(
                f"POST {path} failed with HTTP {error.code}: {detail}")
            if error.code < 500:
                raise last_error from error
        except (urllib.error.URLError, TimeoutError) as error:
            last_error = error
        if attempt < 4:
            time.sleep(attempt * 2)
    raise RuntimeError(f"POST {path} failed after 4 attempts: {last_error}") from last_error


def index_corpus(base_url: str, corpus: dict, timeout: int) -> dict:
    payload = {
        "documentId": corpus["documentId"],
        "chunks": [
            {"sourceId": doc["sourceId"], "title": doc["title"], "content": doc["content"]}
            for doc in corpus["documents"]
        ],
    }
    result = post_json(base_url, "/api/rag/rebuild", payload, timeout)
    expected = len(payload["chunks"])
    if result.get("rebuilt") != expected or len(result.get("ids", [])) != expected:
        raise RuntimeError(f"index verification failed: expected {expected}, got {result}")
    return result


def evaluate(base_url: str, eval_set: dict, strategy: str,
             top_k: int, candidate_k: int, timeout: int, batch_size: int) -> dict:
    cases = eval_set["queries"]
    batch_results = []
    for start in range(0, len(cases), batch_size):
        selected = cases[start:start + batch_size]
        payload = {
            "dataset": eval_set["dataset"],
            "queries": [
                {
                    "caseId": case["caseId"],
                    "query": case["query"],
                    "relevantDocuments": case["relevantDocuments"],
                }
                for case in selected
            ],
            "topK": top_k,
            "candidateK": candidate_k,
            "strategy": strategy,
        }
        result = post_json(base_url, "/api/rag/evaluate", payload, timeout)
        if result.get("totalQueries") != len(selected):
            raise RuntimeError(
                f"strategy {strategy} returned {result.get('totalQueries')} cases; "
                f"expected {len(selected)}")
        batch_results.append(result)

    total = sum(result["totalQueries"] for result in batch_results)
    hit_count = sum(result["hitCount"] for result in batch_results)
    details = [detail for result in batch_results for detail in result["details"]]
    metric_names = ("hitRate", "recall", "precision", "mrr", "ndcg")
    metrics = {"k": top_k}
    for name in metric_names:
        metrics[name] = sum(
            result["metrics"][name] * result["totalQueries"]
            for result in batch_results) / total
    ranked = [detail["topRank"] for detail in details if detail["topRank"] > 0]
    return {
        "dataset": eval_set["dataset"],
        "variant": strategy,
        "strategy": strategy,
        "topK": top_k,
        "candidateK": candidate_k,
        "durationMs": sum(result["durationMs"] for result in batch_results),
        "totalQueries": total,
        "hitCount": hit_count,
        "recallRate": metrics["hitRate"],
        "avgRank": sum(ranked) / len(ranked) if ranked else 0.0,
        "avgFinalScore": sum(
            result["avgFinalScore"] * result["totalQueries"]
            for result in batch_results) / total,
        "metrics": metrics,
        "details": details,
        "batchCount": len(batch_results),
    }


def git_commit() -> str:
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
        capture_output=True, text=True)
    return completed.stdout.strip()


def render_report(run: dict) -> str:
    lines = [
        "# ZhiWei RAG 检索策略真实对比报告",
        "",
        "> 本报告由脚本调用运行中的 `/api/rag/evaluate` 生成；数值来自实际 pgvector/关键词/RRF 检索，不是示例或估算。",
        "",
        "## 可复现元数据",
        "",
        f"- 生成时间（UTC）：`{run['generatedAt']}`",
        f"- Git commit：`{run['gitCommit']}`",
        f"- API：`{run['baseUrl']}`",
        f"- 数据集：`{run['dataset']}`",
        f"- 冻结语料：{run['validation']['documents']} 条，SHA-256 `{run['corpusSha256']}`",
        f"- 静态标注集：{run['validation']['queries']} 条 / {run['validation']['judgments']} 个相关性判断，SHA-256 `{run['evalSetSha256']}`",
        f"- 多文档查询：{run['validation']['multiDocumentQueries']} 条",
        f"- 参数：`topK={run['topK']}`，`candidateK={run['candidateK']}`，查询改写关闭（策略评测走 `searchRaw`）",
        "- 相关度：3=直接充分，2=重要补充，1=边缘相关；nDCG gain 使用 `2^grade-1`。",
        "",
        "## 汇总",
        "",
        "| 策略 | HitRate@K | Recall@K | Precision@K | MRR@K | nDCG@K | 耗时(ms) |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for strategy in run["strategies"]:
        result = run["results"][strategy]
        metrics = result["metrics"]
        lines.append(
            f"| {strategy} | {metrics['hitRate']:.4f} | {metrics['recall']:.4f} | "
            f"{metrics['precision']:.4f} | {metrics['mrr']:.4f} | {metrics['ndcg']:.4f} | "
            f"{result['durationMs']} |")

    baseline = run["results"]["HYBRID"]["metrics"] if "HYBRID" in run["results"] else None
    if baseline:
        lines.extend(["", "## 相对 HYBRID 的变化", "",
                      "| 策略 | ΔHitRate | ΔRecall | ΔPrecision | ΔMRR | ΔnDCG |",
                      "|---|---:|---:|---:|---:|---:|"])
        for strategy in run["strategies"]:
            metrics = run["results"][strategy]["metrics"]
            lines.append(
                f"| {strategy} | {metrics['hitRate'] - baseline['hitRate']:+.4f} | "
                f"{metrics['recall'] - baseline['recall']:+.4f} | "
                f"{metrics['precision'] - baseline['precision']:+.4f} | "
                f"{metrics['mrr'] - baseline['mrr']:+.4f} | "
                f"{metrics['ndcg'] - baseline['ndcg']:+.4f} |")

    winner = max(
        run["strategies"],
        key=lambda item: (
            run["results"][item]["metrics"]["ndcg"],
            run["results"][item]["metrics"]["mrr"],
            run["results"][item]["metrics"]["recall"],
        ),
    )
    lines.extend([
        "",
        "## 结论与边界",
        "",
        f"- 以 nDCG、MRR、Recall 顺序判定，本次冻结语料上的最佳策略是 **{winner}**。",
        "- 该结论仅适用于报告记录的语料快照、Embedding 模型和参数；更换模型或重新分块后必须重跑。",
        "- 当前标注由 Hermes Agent 静态逐项编写，未从检索结果反推；尚未冒充人类复核。发布为正式人工基线前，应由两名领域人员抽检至少 20% 并记录分歧裁决。",
        "- 原始逐查询结果保存在同目录 JSON 文件，可审计每个 case 的排名和指标。",
        "",
    ])
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--eval-set", type=Path, default=DEFAULT_EVAL_SET)
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--output-dir", type=Path, default=ROOT / "evaluation" / "rag" / "reports")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--candidate-k", type=int, default=20)
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--batch-size", type=int, default=10)
    parser.add_argument("--index", action="store_true", help="replace documentId in the live index before evaluation")
    parser.add_argument("--validate-only", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    corpus = load_json(args.corpus)
    eval_set = load_json(args.eval_set)
    validation = validate(corpus, eval_set)
    if args.validate_only:
        print(json.dumps(validation, ensure_ascii=False))
        return 0

    indexed = index_corpus(args.base_url, corpus, args.timeout) if args.index else None
    results = {}
    for strategy in STRATEGIES:
        print(f"evaluating {strategy}...", file=sys.stderr, flush=True)
        results[strategy] = evaluate(
            args.base_url, eval_set, strategy, args.top_k, args.candidate_k,
            args.timeout, args.batch_size)

    generated_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    run = {
        "generatedAt": generated_at,
        "gitCommit": git_commit(),
        "baseUrl": args.base_url,
        "dataset": eval_set["dataset"],
        "topK": args.top_k,
        "candidateK": args.candidate_k,
        "corpusSha256": sha256(args.corpus),
        "evalSetSha256": sha256(args.eval_set),
        "validation": validation,
        "indexResult": indexed,
        "strategies": list(STRATEGIES),
        "results": results,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    stamp = generated_at[:10]
    raw_path = args.output_dir / f"{eval_set['dataset']}-{stamp}-raw.json"
    report_path = args.output_dir / f"{eval_set['dataset']}-{stamp}-comparison.md"
    raw_path.write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report_path.write_text(render_report(run), encoding="utf-8")
    print(json.dumps({
        "raw": str(raw_path),
        "report": str(report_path),
        "validation": validation,
        "metrics": {name: result["metrics"] for name, result in results.items()},
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
