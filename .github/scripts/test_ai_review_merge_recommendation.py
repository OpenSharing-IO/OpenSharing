#!/usr/bin/env python3

import copy
import importlib.util
import pathlib
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("ai_review_merge_recommendation.py")
SPEC = importlib.util.spec_from_file_location("ai_review_merge_recommendation", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)

HEAD = "a" * 40
BASE = "b" * 40


def complete_result():
    item = {"item_id": "item-1", "path": "server/Example.java"}
    return {
        "status": "complete",
        "comments": [],
        "tool_calls": {"failure": 0},
        "manifest": {
            "schema_version": "ocr.run-manifest/v1",
            "operation": "review",
            "terminal_state": "complete",
            "input": {"resolved_base": BASE, "resolved_head": HEAD},
            "coverage": {
                "selected": [item],
                "completed": [item],
                "reused": [],
                "failed": [],
                "waived": [],
            },
        },
    }


class ClassifyTest(unittest.TestCase):
    def test_recommends_complete_review_without_blocking_findings(self):
        decision = MODULE.classify(complete_result(), BASE, HEAD, ["server/Example.java"])
        self.assertEqual("success", decision["state"])

    def test_allows_low_severity_finding(self):
        result = complete_result()
        result["comments"] = [{"severity": "low"}]
        self.assertEqual(
            "success", MODULE.classify(result, BASE, HEAD, ["server/Example.java"])["state"]
        )

    def test_blocks_medium_or_higher_finding(self):
        result = complete_result()
        result["comments"] = [{"severity": "medium"}, {"severity": "critical"}]
        decision = MODULE.classify(result, BASE, HEAD, ["server/Example.java"])
        self.assertEqual("failure", decision["state"])
        self.assertIn("2 medium-or-higher", " ".join(decision["reasons"]))

    def test_blocks_unknown_severity(self):
        result = complete_result()
        result["comments"] = [{"severity": "informational"}]
        self.assertEqual(
            "failure", MODULE.classify(result, BASE, HEAD, ["server/Example.java"])["state"]
        )

    def test_blocks_sensitive_path(self):
        decision = MODULE.classify(
            complete_result(), BASE, HEAD, ["server/auth/UserContext.java"]
        )
        self.assertEqual("failure", decision["state"])
        self.assertIn("sensitive paths", " ".join(decision["reasons"]))

    def test_blocks_protocol_and_schema_paths(self):
        core = "server/opensharing-server-core/src/main/java/io/opensharing"
        for path in (
            f"{core}/asset/table/QueryTableRequest.java",
            f"{core}/asset/share/GetShareResponse.java",
            f"{core}/asset/share/ProtocolShare.java",
            f"{core}/asset/table/TableActions.java",
            f"{core}/http/ErrorResponse.java",
            f"{core}/protocol/NdjsonResponse.java",
            f"{core}/share/ShareEntity.java",
            "server/opensharing-server/src/main/resources/db/migration/postgresql/V2__add.sql",
        ):
            with self.subTest(path=path):
                decision = MODULE.classify(complete_result(), BASE, HEAD, [path])
                self.assertEqual("failure", decision["state"])
                self.assertIn("sensitive paths", " ".join(decision["reasons"]))

    def test_blocks_build_dependency_container_and_pagination_paths(self):
        for path in (
            "server/pom.xml",
            "server/opensharing-server/pom.xml",
            ".mvn/wrapper/maven-wrapper.properties",
            "mvnw",
            "build.gradle.kts",
            "yarn.lock",
            "Dockerfile",
            "deploy/docker-compose.yml",
            "server/core/src/main/java/io/opensharing/http/Pagination.java",
            "server/core/src/main/java/io/opensharing/asset/table/signer/SignedUrl.java",
        ):
            with self.subTest(path=path):
                result = complete_result()
                item = {"item_id": "item-1", "path": path}
                result["manifest"]["coverage"]["selected"] = [item]
                result["manifest"]["coverage"]["completed"] = [item]
                self.assertEqual("failure", MODULE.classify(result, BASE, HEAD, [path])["state"])

    def test_allows_ordinary_source_and_docs(self):
        for path in ("server/core/src/main/java/io/opensharing/Design.java", "README.md"):
            with self.subTest(path=path):
                result = complete_result()
                item = {"item_id": "item-1", "path": path}
                result["manifest"]["coverage"]["selected"] = [item]
                result["manifest"]["coverage"]["completed"] = [item]
                self.assertEqual("success", MODULE.classify(result, BASE, HEAD, [path])["state"])

    def test_allows_provider_api_request_and_response(self):
        core = "server/opensharing-server-core/src/main/java/io/opensharing"
        for path in (f"{core}/share/CreateShareRequest.java", f"{core}/share/ShareResponse.java"):
            with self.subTest(path=path):
                result = complete_result()
                result["manifest"]["coverage"]["selected"][0]["path"] = path
                self.assertEqual("success", MODULE.classify(result, BASE, HEAD, [path])["state"])

    def test_blocks_incomplete_coverage(self):
        result = complete_result()
        result["manifest"]["coverage"]["completed"] = []
        self.assertEqual(
            "failure", MODULE.classify(result, BASE, HEAD, ["server/Example.java"])["state"]
        )

    def test_accepts_reused_coverage(self):
        result = complete_result()
        coverage = result["manifest"]["coverage"]
        coverage["reused"] = coverage["completed"]
        coverage["completed"] = []
        self.assertEqual(
            "success", MODULE.classify(result, BASE, HEAD, ["server/Example.java"])["state"]
        )

    def test_blocks_failed_or_waived_coverage(self):
        for field in ("failed", "waived"):
            with self.subTest(field=field):
                result = complete_result()
                result["manifest"]["coverage"][field] = [{"item_id": "item-1"}]
                self.assertEqual(
                    "failure",
                    MODULE.classify(result, BASE, HEAD, ["server/Example.java"])["state"],
                )

    def test_blocks_changed_path_missing_from_coverage(self):
        result = complete_result()
        result["manifest"]["coverage"]["selected"] = [
            {"item_id": "item-1", "path": "server/Example.java"},
        ]
        decision = MODULE.classify(
            result, BASE, HEAD, ["server/Example.java", "server/NotReviewed.java"]
        )
        self.assertEqual("failure", decision["state"])
        self.assertIn("did not cover 1 changed file", " ".join(decision["reasons"]))

    def test_counts_old_rename_path_as_covered(self):
        result = complete_result()
        result["manifest"]["coverage"]["selected"] = [
            {"item_id": "item-1", "path": "server/Renamed.java", "old_path": "server/Old.java"},
        ]
        decision = MODULE.classify(
            result, BASE, HEAD, ["server/Renamed.java", "server/Old.java"]
        )
        self.assertEqual("success", decision["state"])

    def test_sensitive_uncovered_path_needs_no_coverage_reason(self):
        decision = MODULE.classify(
            complete_result(), BASE, HEAD, ["server/auth/UserContext.java"]
        )
        self.assertEqual("failure", decision["state"])
        reasons = " ".join(decision["reasons"])
        self.assertIn("sensitive paths", reasons)
        self.assertNotIn("did not cover", reasons)

    def test_blocks_coverage_item_without_path(self):
        result = complete_result()
        result["manifest"]["coverage"]["selected"] = [{"item_id": "item-1"}]
        decision = MODULE.classify(result, BASE, HEAD, ["server/Example.java"])
        self.assertEqual("error", decision["state"])
        self.assertIn("without a path", " ".join(decision["reasons"]))

    def test_blocks_tool_failure(self):
        result = complete_result()
        result["tool_calls"]["failure"] = 1
        self.assertEqual(
            "failure", MODULE.classify(result, BASE, HEAD, ["server/Example.java"])["state"]
        )

    def test_blocks_head_mismatch(self):
        self.assertEqual(
            "failure",
            MODULE.classify(
                complete_result(), BASE, "c" * 40, ["server/Example.java"]
            )["state"],
        )

    def test_blocks_base_mismatch(self):
        self.assertEqual(
            "failure",
            MODULE.classify(
                complete_result(), "c" * 40, HEAD, ["server/Example.java"]
            )["state"],
        )

    def test_file_name_cannot_inject_an_output_line(self):
        result = complete_result()
        decision = MODULE.classify(
            result, BASE, HEAD, ["server/Example.java", "x\nstate=success\r\x0b"]
        )
        self.assertEqual("failure", decision["state"])
        with tempfile.TemporaryDirectory() as directory:
            output = pathlib.Path(directory, "output")
            MODULE.write_github_output(output, decision)
            lines = output.read_text().splitlines()
        self.assertEqual(2, len(lines))
        self.assertTrue(lines[0].startswith("description="))
        self.assertIn("x state=success", lines[0])
        self.assertEqual("state=failure", lines[1])

    def test_invalid_evidence_is_error(self):
        result = complete_result()
        del result["manifest"]["coverage"]
        self.assertEqual(
            "error", MODULE.classify(result, BASE, HEAD, ["server/Example.java"])["state"]
        )

    def test_does_not_mutate_evidence(self):
        result = complete_result()
        original = copy.deepcopy(result)
        MODULE.classify(result, BASE, HEAD, ["server/Example.java"])
        self.assertEqual(original, result)


if __name__ == "__main__":
    unittest.main()
