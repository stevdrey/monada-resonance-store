# Pull Request Size Guard

Monada Resonance Store favors small, independently reviewable pull requests. The
[`Pull Request Size Guard`](../../.github/workflows/pr-size-guard.yml) workflow
checks every pull request targeting `main` (including draft PRs and documentation-only
changes) without checking out or executing the proposed source code.

## Policy

| Changed files in the PR | Result |
| --- | --- |
| 0–30 | Pass |
| 31–40 | Pass with a warning |
| 41 or more | Fail: split the PR before merging |

The count comes from GitHub's `pulls.get().data.changed_files` metadata. It covers
all changed files, including added, modified, deleted, renamed, test, migration,
documentation, and generated files. Exactly **40** files are allowed. The workflow
uses a read-only `pull-requests: read` token and makes no source checkout.

Do not delete meaningful tests, security controls, or required documentation to
pass the limit. Instead, divide larger changes into coherent, independently testable
PRs, with explicit dependencies if a sequence is necessary. Each PR should
preserve a buildable and secure intermediate state.

## Planning-time split and local preflight

Before implementing an Issue, list likely affected file groups and estimate the complete
PR footprint, including tests, documentation, generated files, and deleted paths. Aim for
30 files or fewer. If the estimate exceeds 40, stop before implementation. Propose a
dependency-ordered sequence of separately testable child Issues, each using
`docs/specs/spec-context-template.md` to define allowed files, clear non-goals,
acceptance criteria, tests, and integration sequence. Agents may create child Issues
when authorized. Child PRs reference the parent but close only their own child;
close the parent only after all children merge and pass the combined acceptance
criteria. Every intermediate state must be buildable and safe.

The repository includes a shared Node.js 22+ preflight script and compatibility
hooks for Claude Code and Codex:

```bash
git fetch origin main
node .agents/scripts/pr-size-preflight.mjs
node --test .agents/scripts/pr-size-preflight.test.mjs
```

The manually invoked script counts the diff from the merge-base of `origin/main`
(including committed, staged and unstaged paths) and non-ignored untracked paths.
A custom base can be provided as `--base <git-ref>` for intentionally stacked PRs,
but the eventual PR must still satisfy the guard against its actual target.

- Claude Code uses `.claude/settings.json`; Codex uses `.codex/hooks.json`,
  each delegating to `.agents/scripts/pr-size-preflight.mjs --hook` on
  supported `PreToolUse` Bash invocations.
- Hooks check recognizable `git push` and `gh pr create` publication commands.
  They do **not** infer future file changes at planning time, and they cannot
  intercept arbitrary tools, API calls, renamed commands or provider versions
  that do not load these settings. Agents must use the explicit planning policy,
  and CI remains authoritative.
- If `origin/main` is unavailable, preflight fails closed for publishing:
  refresh it with `git fetch origin main` and retry.
- Workflows run after PR creation or update, so a violation blocks merging only
  when the check is **required** on `main`.

## Enable required enforcement on `main`

**Repository admin action required:** a failing workflow alone does not prevent
merging until its job becomes a required status check.

1. Merge the workflow PR so the check definition is present on `main`. Ensure
   the `Pull Request Size Guard / PR file count` job has run on at least one PR.
2. Open **Settings → Rules → Rulesets → New ruleset → New branch ruleset**.
3. Name the ruleset **Require PR size guard**, set enforcement to **Active**,
   and target the branch `main` (not all branches).
4. Enable **Require a pull request before merging** to protect against direct
   pushes that would skip PR checks. To match Dokene's existing ruleset also enable
   **Require conversation resolution before merging**, disallow force pushes
   (`non_fast_forward`), and keep bypass actors empty.
5. Enable **Require status checks to pass before merging**. Add the job named
   **PR file count** from **GitHub Actions** as a required status check. Dokene's
   reference ruleset does not require branches to be up to date before merging.
6. Save the ruleset. Avoid routine bypass actors if the limit must apply to
   administrators and collaborators alike.
7. Verify with a PR changing 41 files: the job must fail and GitHub must block
   merging. Verify that a PR changing 40 files succeeds.

If the status check is not offered yet, run a PR that targets `main`, then
refresh the ruleset editor. The required check is the **job name**
(`PR file count`), not merely the workflow name (`Pull Request Size Guard`).
Keep this name unique across workflows.

GitHub Actions executes after a PR opens or updates; the protection blocks
**merging**, not PR creation. Warnings at 31–40 files do not block merging.
Branch-protection/ruleset configuration is an administrator action and is not
deployed by the workflow PR itself.

## Troubleshooting

- **Check absent on a PR:** confirm the PR targets `main`, that GitHub Actions
  is enabled, and that the job ran after the workflow became available.
- **Limit appears wrong:** inspect the PR's *Files changed* tab, which includes
  more than just application source files.
- **API failure:** the status fails closed instead of treating an unknown count
  as a pass.
