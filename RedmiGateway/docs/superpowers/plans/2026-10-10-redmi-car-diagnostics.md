# Redmi car diagnostics Implementation Plan

> **For agentic workers:** Execute the reviewed, read-only diagnostic task; the user authorized autonomous completion.

**Goal:** Prepare repeatable, private Redmi capability and battery snapshots without changing a working phone bridge.

**Architecture:** A PowerShell collector selects an explicitly named authorized ADB serial and writes only allowlisted fields. No background process or device changes.

**Tech Stack:** Windows PowerShell / PowerShell 7, installed Android platform tools.

**Spec:** `.orchestrator/TEAM_REVIEW_20261010.md`.

## Global Constraints

- Phone serial defaults to 17df6357; absent/offline/unauthorized state fails before shell queries.
- No personally sensitive raw diagnostic output, automatic root/driver/settings changes, ROM flash or install.
- Snapshots are not proof of battery wear, discharge rate, repaired audio or car video functionality.

## Review Focus

- Disconnected or unauthorized device returns a clear error and creates no misleading report.
- Missing sysfs field is reported unavailable, not zero.
- Explicit units preserve current/voltage/charge semantics; charge counter is remaining charge.
- Package missing or disabled is distinct from a supported projection configuration.
- Charging/USB connection must be recorded so snapshots cannot be called unplugged drain measurements.

### Task 1: Read-only collector

**Files:** Create `RedmiGateway/scripts/device/collect_redmi_capabilities.ps1`.

- [x] Implement bounded ADB invocations and strict allowlisted parsing of OS, USB mode, exact bridge/Android Auto package versions and battery metrics.
- [x] Parse with PowerShell parser, run against the currently absent device, and ensure error/no output artifact.
- [x] Have specialists review privacy, device-selection and evidence wording.
- [ ] Collect on the live device only once authorization becomes available; keep actual-device and car acceptance pending otherwise.

No app behavior changes or UI are part of this diagnostic preparation. The user asked for an app, and that deliverable remains incomplete until an implementable, safe projection path and real-car behavior are established.
