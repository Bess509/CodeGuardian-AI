import unittest

from run_rag_eval import Chunk, QuerySpec, evaluate_chunk_health, evaluate_retrieval


class RagEvalMetricsTest(unittest.TestCase):

    def test_evaluate_chunk_health_reports_size_rule_and_fallback_metrics(self):
        chunks = [
            Chunk(
                chunk_id="chunk-a",
                pipeline="test",
                source_file="rules.md",
                content="alpha beta",
                metadata={"rule_ids": ["RULE-SQL-001"], "token_count": 10, "split_reason": "rule_boundary"},
            ),
            Chunk(
                chunk_id="chunk-b",
                pipeline="test",
                source_file="rules.md",
                content="gamma delta",
                metadata={"rule_ids": ["RULE-SECRET-001", "CWE-798"], "token_count": 20, "split_reason": "token_window"},
            ),
            Chunk(
                chunk_id="chunk-c",
                pipeline="test",
                source_file="rules.md",
                content="epsilon",
                metadata={"rule_ids": [], "split_reason": "paragraph"},
            ),
        ]

        metrics = evaluate_chunk_health(chunks)

        self.assertEqual(3, metrics["chunk_count"])
        self.assertEqual(10.3333, metrics["avg_chunk_tokens"])
        self.assertEqual(20, metrics["p95_chunk_tokens"])
        self.assertEqual(1.0, metrics["avg_rule_ids_per_chunk"])
        self.assertEqual(0.3333, metrics["token_window_fallback_rate"])
        self.assertEqual(0.3333, metrics["chunks_without_detected_rule_boundary_rate"])

    def test_evaluate_retrieval_reports_unique_topk_chunk_count(self):
        top_chunks = [
            Chunk("chunk-a", "test", "rules.md", "RULE-SQL-001 PreparedStatement guidance."),
            Chunk("chunk-a", "test", "rules.md", "RULE-SQL-001 duplicate vector row."),
            Chunk("chunk-b", "test", "rules.md", "RULE-SECRET-001 secret guidance."),
        ]

        class FixedRetriever:
            def search(self, query, top_k=5):
                return [(chunk, 1.0 / (index + 1)) for index, chunk in enumerate(top_chunks)]

        metrics, _ = evaluate_retrieval(FixedRetriever(), [
            QuerySpec("q-test", "SQL guidance", ["RULE-SQL-001"], "risk")
        ])

        self.assertEqual(2.0, metrics["topK_unique_chunk_count"])


if __name__ == "__main__":
    unittest.main()
