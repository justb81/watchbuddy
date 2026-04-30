#!/usr/bin/env python3
"""Validates security properties of .github/workflows/release.yml.

Checks that signing credentials are not exposed as plain env vars in the
Gradle build step, that secrets are re-masked, that a cleanup step exists,
and that concurrency is configured to prevent overlapping releases.
"""

import sys
import yaml

WORKFLOW_PATH = ".github/workflows/release.yml"
SIGNING_SECRET_KEYS = {"KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD"}


def fail(msg: str) -> None:
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def find_steps(workflow: dict) -> list[dict]:
    steps = []
    for job in workflow.get("jobs", {}).values():
        steps.extend(job.get("steps", []))
    return steps


def main() -> None:
    with open(WORKFLOW_PATH) as f:
        workflow = yaml.safe_load(f)

    # 1. Workflow-level concurrency must be configured.
    concurrency = workflow.get("concurrency")
    if not concurrency:
        fail("release.yml is missing a top-level 'concurrency:' group — overlapping releases can corrupt GitHub Releases and Play edits.")
    if concurrency.get("cancel-in-progress", True) is True:
        fail("release.yml concurrency.cancel-in-progress must be false — cancelling an in-progress release can leave artifacts in an inconsistent state.")

    steps = find_steps(workflow)

    # 2. A masking step must emit ::add-mask:: for every signing secret.
    mask_steps = [
        s for s in steps
        if s.get("run") and "add-mask" in s.get("run", "")
    ]
    if not mask_steps:
        fail("No '::add-mask::' step found — signing secrets must be re-masked before any Gradle invocation.")
    for key in SIGNING_SECRET_KEYS:
        if not any(key in s.get("run", "") for s in mask_steps):
            fail(f"Signing secret {key!r} is not masked via '::add-mask::' in any step.")

    # 3. Signing secrets must NOT appear in env of the Gradle build step.
    build_steps = [
        s for s in steps
        if s.get("run") and "gradlew" in s.get("run", "") and "assembleRelease" in s.get("run", "")
    ]
    if not build_steps:
        fail("Could not locate the Gradle release build step — check validate-release-security.py if the step name changed.")
    for build_step in build_steps:
        env = build_step.get("env") or {}
        leaked = SIGNING_SECRET_KEYS & set(env.keys())
        if leaked:
            fail(
                f"Signing secret(s) {leaked} are set as plain env vars on the Gradle build step. "
                "Write them to ~/.gradle/gradle.properties instead."
            )

    # 4. A cleanup step must run with `if: always()`.
    cleanup_steps = [
        s for s in steps
        if s.get("if") == "always()" and s.get("run") and "release.keystore" in s.get("run", "")
    ]
    if not cleanup_steps:
        fail("No cleanup step found with 'if: always()' that removes /tmp/release.keystore — secrets may persist on a reused runner.")

    # 5. Signing credentials must be written to ~/.gradle/gradle.properties.
    props_steps = [
        s for s in steps
        if s.get("run") and "gradle.properties" in s.get("run", "") and "watchbuddy.signing" in s.get("run", "")
    ]
    if not props_steps:
        fail("No step found that writes 'watchbuddy.signing.*' properties to gradle.properties — signing credentials must not travel as env vars.")

    print(f"OK: {WORKFLOW_PATH} passes all signing-security checks.")


if __name__ == "__main__":
    main()
