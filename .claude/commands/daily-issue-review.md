# Daily Issue Review Orchestrator

You are the orchestrator for the daily issue review pipeline in `justb81/watchbuddy`.
Process every open issue through the correct stage based on its labels.

## Step 1 — Fetch all open issues

Run:
```bash
gh issue list --repo justb81/watchbuddy --state open --json number,title,body,labels --limit 100
```

Parse the result. For each issue, extract:
- `number`
- `title`
- `body`
- `labels` (array of label names)

## Step 2 — Classify and dispatch

For each issue, check labels and spawn the appropriate subagent.
Run all same-stage subagents in parallel; complete all Stage 1 agents before starting
Stage 2 agents, and complete all Stage 2 agents before starting Stage 3 agents
(to avoid a stage-2 issue being immediately re-processed as stage 3 in the same run).

---

### Stage 1 — Raw / unlabeled issues

**Condition:** the issue has **no labels at all** (empty labels array).

For each qualifying issue, use the Agent tool with **model "sonnet"** and this prompt
(replace `{NUMBER}`, `{TITLE}`, `{BODY}` with the actual values):

```
You are improving a raw GitHub issue for the justb81/watchbuddy Android app.
This is a two-app Android ecosystem (phone + Google TV) for series tracking.
Key tech: Kotlin 2.1, Jetpack Compose, Hilt, Retrofit, Room, Trakt API, TMDB API.

Issue #{NUMBER}: {TITLE}
Body:
{BODY}

Your tasks:
1. Fetch the current available labels:
   gh label list --repo justb81/watchbuddy --json name --limit 100

2. Rewrite the issue to be clear and actionable:
   - Clear one-line title (imperative mood, max 72 chars)
   - Problem statement
   - Expected behaviour
   - Acceptance criteria (bullet list)
   Keep the rewrite concise. Do NOT write an implementation plan.

3. Update the issue title and body:
   gh issue edit {NUMBER} --repo justb81/watchbuddy \
     --title "<new title>" \
     --body "<new body>"

4. Add any appropriate EXISTING labels from the list you fetched.
   Do NOT create new labels.
   Use: gh issue edit {NUMBER} --repo justb81/watchbuddy --add-label "<label>"
   Repeat for each label.

5. Add the workflow label:
   gh issue edit {NUMBER} --repo justb81/watchbuddy --add-label "ready for specification"
```

---

### Stage 2 — Ready for specification

**Condition:** the issue has the label `ready for specification`.

For each qualifying issue, use the Agent tool with **model "opus"** and this prompt:

```
You are a software architect writing a detailed implementation plan for a GitHub issue
in the justb81/watchbuddy Android app.
This is a two-app Android ecosystem (phone + Google TV) for series tracking.
Key tech: Kotlin 2.1, Jetpack Compose, Hilt, Retrofit, Room, Trakt API, TMDB API.

Issue #{NUMBER}: {TITLE}
Body:
{BODY}

Your tasks:
1. Read the full issue:
   gh issue view {NUMBER} --repo justb81/watchbuddy

2. Explore the relevant source files to understand the current implementation.
   Use the Read tool and Bash (find/grep) to navigate the codebase.
   Pay attention to: module structure (app-phone/, app-tv/, core/),
   existing ViewModels, Repositories, Hilt modules, and API services.

3. Write a comprehensive implementation plan as a GitHub comment.
   The plan MUST include:
   - Exact file paths to create or modify (relative to repo root)
   - For each file: what specifically needs to change (code snippets or pseudocode)
   - New dependencies to add to gradle/libs.versions.toml (if any)
   - Migration / database schema changes (if any)
   - Localisation changes required (values/, values-de/, values-fr/, values-es/)
   - Unit test considerations
   - Ordered list of changes (which file to touch first, etc.)

   Post the plan as a comment:
   gh issue comment {NUMBER} --repo justb81/watchbuddy --body "<plan in markdown>"

4. Transition the issue to the next stage:
   gh issue edit {NUMBER} --repo justb81/watchbuddy \
     --remove-label "ready for specification" \
     --add-label "ready to implement"
```

---

### Stage 3 — Ready to implement

**Condition:** the issue has the label `ready to implement`.

For each qualifying issue, use the Agent tool with **model "sonnet"** and this prompt:

```
You are implementing GitHub issue #{NUMBER} in the justb81/watchbuddy Android app.
Follow the CLAUDE.md conventions exactly.

Your tasks:

1. Check for concurrent agent work (mandatory first step):
   gh pr list --state open --repo justb81/watchbuddy
   Scan PR titles and bodies for "Closes #{{NUMBER}}", "Fixes #{{NUMBER}}", etc.
   If a matching open PR exists, post a comment and stop:
   gh issue comment {NUMBER} --repo justb81/watchbuddy \
     --body "A PR is already open for this issue — see #<PR>. Skipping."

2. Read the issue and implementation plan:
   gh issue view {NUMBER} --repo justb81/watchbuddy --comments

3. Mark the issue as being worked on (prevents duplicate processing):
   gh issue edit {NUMBER} --repo justb81/watchbuddy --add-label "in progress"
   (Only add this label if it exists — check with gh label list first.)

4. Create a feature branch from main:
   git fetch origin main
   git checkout -b feature/issue-{NUMBER}-<short-slug> origin/main

5. Implement all changes specified in the implementation plan exactly.
   - Follow Kotlin code style (official), Compose patterns, Hilt, StateFlow
   - All text in English
   - No unnecessary comments or TODOs

6. Before every commit, run pre-commit checks:
   git config core.hooksPath .githooks
   ./scripts/precommit.sh
   (Gradle scope will be skipped if Android SDK is absent; CI is the real gate.)

7. Commit using Conventional Commits (feat:, fix:, chore:, etc.):
   git add <specific files>
   git commit -m "feat: <description>

   Closes #{NUMBER}
   https://claude.ai/code/session_013PNZkpfbQfqXhidTsziQak"

8. Push the branch:
   git push -u origin feature/issue-{NUMBER}-<short-slug>

9. Create a PR:
   gh pr create \
     --repo justb81/watchbuddy \
     --title "<concise title, max 70 chars>" \
     --body "$(cat <<'EOF'
## Summary
- <bullet 1>
- <bullet 2>

## Test plan
- [ ] <test step>

Closes #{NUMBER}

> Note: Gradle scope skipped locally (no Android SDK in CI runner); CI is the gate.

https://claude.ai/code/session_013PNZkpfbQfqXhidTsziQak
EOF
)"

10. Wait for CI and fix failures:
    Poll every 60 seconds:
    gh pr checks <PR-NUMBER> --repo justb81/watchbuddy
    If any required check fails, read the logs and fix the issue:
    gh pr view <PR-NUMBER> --repo justb81/watchbuddy --comments
    Then push a fix commit and repeat.

11. When all required checks pass (status = completed, conclusion = success):
    gh pr merge <PR-NUMBER> --squash --delete-branch --repo justb81/watchbuddy

    If a reviewer has requested changes, do NOT merge — post a summary comment
    and stop, leaving the PR open for human review.
```

---

## Notes

- Process at most 10 issues total per run to stay within time and API limits.
  Prioritise in order: Stage 3 → Stage 2 → Stage 1.
- Do not create new labels; only use labels already present in the repository
  plus `ready for specification` and `ready to implement` (which the workflow
  ensures exist before this command runs).
- All output (issue bodies, PR descriptions, comments) must be in English.
- If any subagent reports an unrecoverable error, log a brief comment on that
  issue and continue with the remaining issues.
