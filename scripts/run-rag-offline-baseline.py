#!/usr/bin/env python3
"""Deterministic offline RAG baseline when the live pgvector stack is unavailable."""

import collections
import datetime as dt
import hashlib
import json
import math
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVAL_PATH = ROOT / "evaluation" / "rag" / "eval-set-v1.json"
CORPUS_PATH = ROOT / "evaluation" / "rag" / "corpus-v1.json"
REPORT_DIR = ROOT / "evaluation" / "rag" / "reports"
TOKEN_PATTERN = re.compile(r"[a-z0-9_.:+-]+|[\u4e00-\u9fff]", re.IGNORECASE)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tokens(text: str) -> list[str]:
    units = TOKEN_PATTERN.findall(text.lower())
    chinese = [unit for unit in units if "\u4e00" <= unit <= "\u9fff"]
    ascii_tokens = [unit for unit in units if not ("\u4e00" <= unit <= "\u9fff")]
    bigrams = [chinese[index] + chinese[index + 1] for index in range(len(chinese) - 1)]
    return ascii_tokens + chinese + bigrams


def char_ngrams(text: str, n: int = 3) -> collections.Counter[str]:
    normalized = "".join(TOKEN_PATTERN.findall(text.lower()))
    if len(normalized) < n:
        return collections.Counter([normalized]) if normalized else collections.Counter()
    return collections.Counter(normalized[index:index + n] for index in range(len(normalized) - n + 1))


def cosine(left: collections.Counter[str], right: collections.Counter[str]) -> float:
    if not left or not right:
        return 0.0
    dot = sum(value * right.get(term, 0) for term, value in left.items())
    left_norm = math.sqrt(sum(value * value for value in left.values()))
    right_norm = math.sqrt(sum(value * value for value in right.values()))
    return dot / (left_norm * right_norm) if left_norm and right_norm else 0.0


class OfflineIndex:
    def __init__(self, documents: list[dict]):
        self.documents = documents
        self.tokenized = [tokens(doc["title"] + " " + doc["content"]) for doc in documents]
        self.term_counts = [collections.Counter(items) for items in self.tokenized]
        self.doc_lengths = [len(items) for items in self.tokenized]
        self.avg_length = sum(self.doc_lengths) / len(self.doc_lengths)
        self.char_vectors = [char_ngrams(doc["title"] + " " + doc["content"]) for doc in documents]
        self.document_frequency = collections.Counter()
        for terms in self.term_counts:
            self.document_frequency.update(terms.keys())

    def bm25(self, query: str) -> list[str]:
        query_terms = collections.Counter(tokens(query))
        count = len(self.documents)
        scores = []
        for index, term_count in enumerate(self.term_counts):
            score = 0.0
            for term, query_frequency in query_terms.items():
                frequency = term_count.get(term, 0)
                if frequency == 0:
                    continue
                document_frequency = self.document_frequency[term]
                inverse_document_frequency = math.log(
                    1.0 + (count - document_frequency + 0.5) / (document_frequency + 0.5))
                denominator = frequency + 1.5 * (
                    1.0 - 0.75 + 0.75 * self.doc_lengths[index] / self.avg_length)
                score += query_frequency * inverse_document_frequency * frequency * 2.5 / denominator
            scores.append(score)
        return self._rank(scores)

    def char_ngram(self, query: str) -> list[str]:
        query_vector = char_ngrams(query)
        return self._rank([cosine(query_vector, vector) for vector in self.char_vectors])

    def rrf_hybrid(self, query: str) -> list[str]:
        bm25_ranking = self.bm25(query)
        char_ranking = self.char_ngram(query)
        scores = collections.defaultdict(float)
        for rank, source_id in enumerate(bm25_ranking, start=1):
            scores[source_id] += 1.0 / (60 + rank)
        for rank, source_id in enumerate(char_ranking, start=1):
            scores[source_id] += 1.0 / (60 + rank)
        return sorted(scores, key=lambda source_id: (-scores[source_id], source_id))

    def _rank(self, scores: list[float]) -> list[str]:
        ranked = sorted(
            range(len(self.documents)),
            key=lambda index: (-scores[index], self.documents[index]["sourceId"]),
        )
        return [self.documents[index]["sourceId"] for index in ranked]


def query_metrics(retrieved: list[str], judgments: list[dict], k: int) -> dict:
    relevance = {item["sourceId"]: item["relevance"] for item in judgments}
    seen = set()
    matched = 0
    first_rank = -1
    dcg = 0.0
    for index, source_id in enumerate(retrieved[:k], start=1):
        if source_id in seen:
            continue
        seen.add(source_id)
        grade = relevance.get(source_id)
        if grade is None:
            continue
        matched += 1
        first_rank = index if first_rank < 0 else first_rank
        dcg += (2 ** grade - 1) / math.log2(index + 1)
    ideal = sum(
        (2 ** grade - 1) / math.log2(index + 2)
        for index, grade in enumerate(sorted(relevance.values(), reverse=True)[:k])
    )
    return {
        "hitRate": 1.0 if matched else 0.0,
        "recall": matched / len(relevance),
        "precision": matched / k,
        "mrr": 1.0 / first_rank if first_rank > 0 else 0.0,
        "ndcg": dcg / ideal if ideal else 0.0,
        "firstRelevantRank": first_rank,
        "matchedRelevantCount": matched,
    }


def evaluate(index: OfflineIndex, cases: list[dict], strategy: str, k: int) -> dict:
    started = time.perf_counter()
    retrieve = {
        "BM25": index.bm25,
        "CHAR_TRIGRAM": index.char_ngram,
        "RRF_HYBRID": index.rrf_hybrid,
    }[strategy]
    details = []
    for case in cases:
        ranking = retrieve(case["query"])
        metrics = query_metrics(ranking, case["relevantDocuments"], k)
        details.append({
            "caseId": case["caseId"],
            "category": case["category"],
            "query": case["query"],
            "relevantDocuments": case["relevantDocuments"],
            "retrievedSourceIds": ranking[:k],
            "metrics": metrics,
        })
    metric_names = ("hitRate", "recall", "precision", "mrr", "ndcg")
    metrics = {"k": k}
    metrics.update({
        name: sum(detail["metrics"][name] for detail in details) / len(details)
        for name in metric_names
    })
    return {
        "strategy": strategy,
        "durationMs": round((time.perf_counter() - started) * 1000, 3),
        "totalQueries": len(cases),
        "metrics": metrics,
        "details": details,
    }


def render(run: dict) -> str:
    lines = [
        "# ZhiWei RAG 离线检索策略真实对比报告",
        "",
        "> 本报告由脚本在冻结语料上实际执行 BM25、字符三元组余弦和 RRF 融合生成；不是示例数据。由于运行时远程 PostgreSQL 在实测中断开，本报告不冒充 pgvector 在线结果。",
        "",
        "## 可复现元数据",
        "",
        f"- 生成时间（UTC）：`{run['generatedAt']}`",
        f"- Git commit：`{run['gitCommit']}`",
        f"- 语料 SHA-256：`{run['corpusSha256']}`（{run['documents']} 条）",
        f"- 标注集 SHA-256：`{run['evalSetSha256']}`（{run['queries']} 条，{run['judgments']} 个判断）",
        f"- 多文档查询：{run['multiDocumentQueries']} 条",
        f"- 参数：`topK={run['topK']}`；BM25(k1=1.5,b=0.75)，字符三元组余弦，RRF(k=60)。",
        "",
        "## 汇总",
        "",
        "| 策略 | HitRate@5 | Recall@5 | Precision@5 | MRR@5 | nDCG@5 | 耗时(ms) |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name in run["strategies"]:
        result = run["results"][name]
        metric = result["metrics"]
        lines.append(
            f"| {name} | {metric['hitRate']:.4f} | {metric['recall']:.4f} | "
            f"{metric['precision']:.4f} | {metric['mrr']:.4f} | {metric['ndcg']:.4f} | "
            f"{result['durationMs']:.3f} |")
    winner = max(
        run["strategies"],
        key=lambda name: (
            run["results"][name]["metrics"]["ndcg"],
            run["results"][name]["metrics"]["mrr"],
            run["results"][name]["metrics"]["recall"],
        ),
    )
    lines.extend([
        "",
        "## 结论与边界",
        "",
        f"- 按 nDCG、MRR、Recall 排序，本次离线基线最佳策略为 **{winner}**。",
        "- 结果证明评测数据、五项指标、逐查询审计和报告链路可运行；它不能替代 pgvector + Embedding 在线基线。",
        "- `scripts/run-rag-evaluation.py --index` 是在线评测入口；数据库恢复后应重跑并提交在线报告。",
        "- 标注由 Hermes Agent 静态逐项编写，未从检索结果反推；正式对外使用前仍需两名领域人员抽检至少 20%。",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    corpus = json.loads(CORPUS_PATH.read_text(encoding="utf-8"))
    eval_set = json.loads(EVAL_PATH.read_text(encoding="utf-8"))
    documents = corpus["documents"]
    cases = eval_set["queries"]
    source_ids = {doc["sourceId"] for doc in documents}
    if not 100 <= len(cases) <= 300:
        raise ValueError("evaluation case count must be between 100 and 300")
    for case in cases:
        for judgment in case["relevantDocuments"]:
            if judgment["sourceId"] not in source_ids:
                raise ValueError(f"unknown sourceId: {judgment['sourceId']}")

    index = OfflineIndex(documents)
    strategies = ("BM25", "CHAR_TRIGRAM", "RRF_HYBRID")
    results = {name: evaluate(index, cases, name, 5) for name in strategies}
    generated_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    run = {
        "executionMode": "deterministic-offline",
        "generatedAt": generated_at,
        "gitCommit": subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
            capture_output=True, text=True).stdout.strip(),
        "corpusSha256": sha256(CORPUS_PATH),
        "evalSetSha256": sha256(EVAL_PATH),
        "documents": len(documents),
        "queries": len(cases),
        "judgments": sum(len(case["relevantDocuments"]) for case in cases),
        "multiDocumentQueries": sum(len(case["relevantDocuments"]) > 1 for case in cases),
        "topK": 5,
        "strategies": list(strategies),
        "results": results,
        "onlineAttempt": {
            "status": "blocked",
            "reason": "live PostgreSQL connections closed during evaluation",
        },
    }
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = generated_at[:10]
    raw_path = REPORT_DIR / f"{eval_set['dataset']}-{stamp}-offline-raw.json"
    report_path = REPORT_DIR / f"{eval_set['dataset']}-{stamp}-offline-comparison.md"
    raw_path.write_text(json.dumps(run, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    report_path.write_text(render(run), encoding="utf-8")
    print(json.dumps({
        "raw": str(raw_path),
        "report": str(report_path),
        "metrics": {name: results[name]["metrics"] for name in strategies},
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
