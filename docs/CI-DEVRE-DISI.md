docs: note that the workflows are disabled while runners are unavailable

All three workflows are disabled_manually. Pushing while they are enabled
creates a failing run, and every failing run notifies the repository owner, so
a routine commit during an outage costs a person an email. Re-enable with
PUT /repos/{owner}/{repo}/actions/workflows/{id}/enable once runners return.

---

## 16.10.2026 16:15 — the archive repo was still enabled (rule 0.1, second time)

`redmi-gateway` was disabled, but **`redmi-gateway-archive` was left active**. Rule 4.2 says
every push goes to both repositories, so every push to the archive created **another failing
run** and therefore **another notification for the user**.

Measured through the GitHub API on 16.10.2026:
```
redmi-gateway-archive / most recent 100 runs, all of them today:
  failure 79   success 21
  by hour (UTC): 08:00 -> 1 | 09:00 -> 27 | 10:00 -> 19 | 11:00 -> 17 | 12:00 -> 34 | 13:00 -> 2
```
The last successful run was at 09:45 UTC. The two runs at 13:05 UTC line up with the pushes
that carried the handover-gate fix.

**Lesson:** "push to both repositories" has to be paired with "check the workflows of both
repositories". Disabling one repository is not enough — the one left enabled keeps producing
notifications silently, and a push that looks harmless is the thing that produces them.

All three workflows in both repositories are now `disabled_manually` (verified at 16:15).
Re-enable in **both** with `PUT /actions/workflows/{id}/enable` once runners return.
