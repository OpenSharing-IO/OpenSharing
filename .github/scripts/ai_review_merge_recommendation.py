#!/usr/bin/env python3
"""Classify whether an OpenCodeReview result is safe to recommend for merging."""

import argparse
import json
import pathlib
import re
from typing import Any


ALLOWED_SEVERITIES = {"low", "medium", "high", "critical"}
BLOCKING_SEVERITIES = {"medium", "high", "critical"}
SENSITIVE_PATH_PATTERNS = (
    re.compile(r"^\.github(?:/|$)", re.IGNORECASE),
    re.compile(r"^spec(?:/|$)", re.IGNORECASE),
    re.compile(r"(?:^|/)(?:security\.md|codeowners)$", re.IGNORECASE),
    re.compile(r"(?:auth|credential|security|token|signer)", re.IGNORECASE),
)


def _item_ids(value: Any, field: str, errors: list[str]) -> set[str]:
    if not isinstance(value, list):
        errors.append(f"manifest.coverage.{field} is not an array")
        return set()

    item_ids = set()
    for item in value:
        if not isinstance(item, dict) or not isinstance(item.get("item_id"), str):
            errors.append(f"manifest.coverage.{field} contains an invalid item")
            continue
        item_ids.add(item["item_id"])
    return item_ids


def classify(
    result: Any,
    expected_base: str,
    expected_head: str,
    changed_paths: Any,
) -> dict[str, Any]:
    errors: list[str] = []
    reasons: list[str] = []

    if not isinstance(result, dict):
        return _decision("error", ["review result is not a JSON object"])
    if not all(
        isinstance(commit, str) and re.fullmatch(r"[0-9a-fA-F]{40}", commit)
        for commit in (expected_base, expected_head)
    ):
        return _decision("error", ["expected base or head is not a full commit SHA"])
    if not isinstance(changed_paths, list) or not changed_paths or not all(
        isinstance(path, str) and path for path in changed_paths
    ):
        return _decision("error", ["changed paths are missing or invalid"])

    manifest = result.get("manifest")
    if not isinstance(manifest, dict):
        return _decision("error", ["review manifest is missing or invalid"])

    if manifest.get("schema_version") != "ocr.run-manifest/v1":
        errors.append("review manifest has an unsupported schema")
    if manifest.get("operation") != "review":
        errors.append("review manifest has an unexpected operation")
    if result.get("status") != "complete" or manifest.get("terminal_state") != "complete":
        reasons.append("review did not complete")

    manifest_input = manifest.get("input")
    if not isinstance(manifest_input, dict):
        errors.append("manifest.input is missing or invalid")
    else:
        if manifest_input.get("resolved_base") != expected_base:
            reasons.append("reviewed base does not match the pull request base")
        if manifest_input.get("resolved_head") != expected_head:
            reasons.append("reviewed commit does not match the pull request head")

    coverage = manifest.get("coverage")
    if not isinstance(coverage, dict):
        errors.append("manifest.coverage is missing or invalid")
    else:
        selected = _item_ids(coverage.get("selected"), "selected", errors)
        completed = _item_ids(coverage.get("completed"), "completed", errors)
        reused = _item_ids(coverage.get("reused"), "reused", errors)
        failed = _item_ids(coverage.get("failed"), "failed", errors)
        waived = _item_ids(coverage.get("waived"), "waived", errors)
        if not selected:
            reasons.append("review selected no files")
        if selected - completed - reused:
            reasons.append("review coverage is incomplete")
        if failed:
            reasons.append("review coverage contains failed files")
        if waived:
            reasons.append("review coverage contains waived files")

    tool_calls = result.get("tool_calls")
    if not isinstance(tool_calls, dict):
        errors.append("tool_calls is missing or invalid")
    else:
        failures = tool_calls.get("failure")
        if not isinstance(failures, int) or isinstance(failures, bool):
            errors.append("tool_calls.failure is missing or invalid")
        elif failures:
            reasons.append("review tool calls failed")

    comments = result.get("comments")
    if not isinstance(comments, list):
        errors.append("comments is missing or invalid")
    else:
        severities = []
        for comment in comments:
            severity = comment.get("severity") if isinstance(comment, dict) else None
            if not isinstance(severity, str) or severity.lower() not in ALLOWED_SEVERITIES:
                reasons.append("review contains a finding with unknown severity")
                continue
            severities.append(severity.lower())
        blocking_count = sum(severity in BLOCKING_SEVERITIES for severity in severities)
        if blocking_count:
            reasons.append(f"review contains {blocking_count} medium-or-higher finding(s)")

    sensitive_paths = sorted(
        path
        for path in changed_paths
        if any(pattern.search(path) for pattern in SENSITIVE_PATH_PATTERNS)
    )
    if sensitive_paths:
        reasons.append("security-sensitive paths require human review")

    if errors:
        return _decision("error", errors + reasons)
    if reasons:
        return _decision("failure", reasons)
    return _decision(
        "success",
        [],
        "AI review complete with full coverage and no blocking findings",
    )


def _decision(
    state: str,
    reasons: list[str],
    description: str | None = None,
) -> dict[str, Any]:
    if description is None:
        prefix = (
            "AI review could not recommend merge"
            if state == "failure"
            else "AI review policy error"
        )
        description = f"{prefix}: {reasons[0]}" if reasons else prefix
    return {
        "state": state,
        "description": description[:140],
        "reasons": reasons,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--result", required=True, type=pathlib.Path)
    parser.add_argument("--changed-paths", required=True, type=pathlib.Path)
    parser.add_argument("--expected-base", required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--github-output", type=pathlib.Path)
    args = parser.parse_args()

    try:
        result = json.loads(args.result.read_text())
        changed_paths = json.loads(args.changed_paths.read_text())
        decision = classify(result, args.expected_base, args.expected_head, changed_paths)
    except (OSError, json.JSONDecodeError) as error:
        decision = _decision("error", [f"could not read review evidence: {error}"])

    print(json.dumps(decision, sort_keys=True))
    if args.github_output:
        with args.github_output.open("a") as output:
            output.write(f"state={decision['state']}\n")
            output.write(f"description={decision['description']}\n")


if __name__ == "__main__":
    main()
