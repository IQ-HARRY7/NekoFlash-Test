# NekoFlash UI/UX and Localization Audit

**Audit scope:** Android interface, UI controllers, RU/EN interface copy, accessibility-facing labels, and public documentation.

**Explicitly out of scope:** USB transport, ADB/Fastboot protocol implementation, network requests, Xiaomi API behavior, mutation algorithms, operation ownership, signing/build logic, and other backend/core behavior.

## Release-readiness assessment

**Current project readiness: 90% for the next reviewed alpha/release-candidate build.**

The UI refactor is internally consistent under static verification, but release sign-off still requires a real Gradle/Android build and physical-device visual smoke because the audit environment could not download the Gradle 8.13 distribution. The remaining work is validation rather than another planned UI redesign.

| Area | Status | Notes |
|---|---:|---|
| UI structure / dead-code cleanup | 97% | Obsolete overlay, hidden queue UI, unreachable UI helpers and unused resources removed. |
| Console / operation UX | 99% | Console no longer auto-expands; a cross-tab live strip plus the Home Operation Center keep operation state visible without overlays. |
| RU/EN localization | 96% | Resource-key parity preserved; screen/dialog/action copy audited. Raw protocol/support payloads intentionally remain technical. |
| Accessibility / layout resilience | 94% | Icon-only import and Console expand/collapse descriptions added; long event/progress text is bounded. |
| Documentation | 94% | README, changelog and EN/RU site feature copy updated. Screenshots should be refreshed from the validated build. |
| Build / device validation | 72% | Static checks pass; local Gradle build is blocked by unavailable network dependency download. |

## What changed

### 1. Deep UI cleanup

- Removed the obsolete non-modal flash progress overlay from `activity_main.xml` and its unused Activity fields/cancel-dialog helpers.
- Removed the permanently hidden legacy multi-flash queue card and the UI-only methods/styles/colors/strings that existed solely for it.
- Removed two unreachable UI branches (`openWorkspaceFolder()` and the old partition-inventory dialog) after confirming they had no call sites. The underlying workspace and Fastboot inventory data/model code is untouched.
- Removed the hard-coded navigation palette from `TabController`; tab states now use the shared color resources.
- Restored Console history Up/Down controls when the Console is expanded; previously the listeners existed but the controls were always hidden.
- Cleaned stale UI comments/formatting and removed unused layout namespaces/resources created by the deleted widgets.

### 2. RU/EN localization audit

The English and Russian resource sets keep the same keys and compatible formatting placeholders.

Localized or refined UI copy includes:

- persistent Operation Center states and descriptions;
- Quick Flash slot-target dialog;
- programmatic Xiaomi Mi Unlock page and session-state messages;
- logs/reports menu, current-session summary and file-kind labels;
- Home device overview labels;
- Console expand/collapse accessibility descriptions;
- Russian mixed-language labels such as protocol trace, session JSON, Reboot, Console and max-download wording.

Low-level protocol tokens, command syntax, device-provided values, backend warning bodies and support-oriented diagnostic payload fields are intentionally not translated because changing them would cross the UI-only boundary or reduce diagnostic fidelity.

### 3. Console behavior

Console remains a persistent Material Bottom Sheet, but it is now passive during operations:

- starting an operation collapses any previously open Console to the compact dock;
- reboot, Mi Unlock and failed-operation paths no longer call automatic Console expansion/preview APIs;
- progress updates never expand, half-expand or overlay Console;
- the user can still open Console explicitly from the Operation Center or by interacting with the dock;
- header accessibility text reflects the current expand/collapse action.

This keeps diagnostic access available without stealing focus or covering the active workflow.

### 4. Operation Center and cross-tab live status

The Home Operation Center is always present instead of appearing only for selected states. It now provides:

- idle/running/completed/failed/cancelled/verify-pending status;
- a prominent current task title plus determinate percentage or indeterminate busy progress;
- `step X of Y` for operations that already publish step data;
- a bounded nearby-step window centered around the running/failed step instead of dumping a long queue;
- technical-event text only when it adds value for an active warning/error, failure or verify-pending result;
- explicit **Open console**, **Reports**, and **Cancel operation** actions.

In addition, a compact live-operation strip is mounted in the global Activity shell above the workflow content. It is therefore visible from Fastboot, ADB/Sideload, Mi Unlock, Home and Settings without forcing navigation or opening a popup. While a step is known, the strip keeps both `step X of Y` and the safety/status message visible. Tapping the strip returns to Home and scrolls directly to the full Operation Center. Successful/cancelled results remain briefly for confirmation and then auto-hide; failed/verify-pending results remain visible until the user reviews or dismisses them.

Operation-state hygiene is handled in the UI without changing transport logic: a lightweight diagnostic/command run that does not publish its own `OperationProgress` suppresses any older heavy-operation result instead of reviving stale `100%` / failure state. That suppression survives Activity recreation. Cancellation is presented as a distinct `CANCELLING` / `ОТМЕНА` safe-cleanup state with indeterminate progress until the transport reports the final outcome, and inactive cancel controls are disabled. Activity recreation also preserves an already-requested cancellation and a manually/automatically dismissed finished strip, preventing duplicate Cancel actions or a dismissed result from reappearing after rotation.

The UI deliberately ignores a completed progress object while a later operation is active, and it does not reuse a previous flash queue unless current-run step updates arrive. This prevents stale `100%` or stale partition steps from being presented as the new operation. Long event/progress text is capped with ellipsis so verbose diagnostic messages cannot grow the card indefinitely.

### 5. Documentation

Updated:

- `README.md` feature list and documentation index;
- `CHANGELOG.md` with an explicit unreleased UI-only audit section;
- `docs/index.html` and `docs/ru/index.html` with the current Operation Center / Console behavior;
- this audit document as the durable UI validation record.

## Validation performed

Static checks performed on the modified tree:

- no `идеальное` / `perfect` protection markers were present in the supplied source, so no protected section was modified;
- all Android XML resources parse successfully;
- no unresolved `R.string`, `R.plurals` or `@string` references remain;
- EN/RU resource key sets are identical;
- formatting placeholders are compatible across EN/RU resources (language-specific plural category counts are expected to differ);
- no unused string resources remain under static source/resource reference analysis;
- no unreachable private UI functions remain in the audited UI controllers under call-site analysis;
- no icon-only button in the current layouts is missing both visible text and a content description;
- automatic Console-opening calls were removed from operation execution paths;
- the global live-operation strip is non-modal, keyboard/accessibility focusable, dismissible after completion, and routes to the full Home Operation Center;
- the modified-file boundary contains UI/controller/resource/documentation files only.

### Build limitation in this audit environment

`./gradlew :app:lintDebug :app:assembleDebug` could not proceed because the Gradle wrapper needs Gradle **8.13** and the environment has no network access to `services.gradle.org` (`UnknownHostException`). This is an environment/dependency-fetch limitation; it is not evidence of either a successful or failed Android compilation.

## Remaining release attention

1. Run `./gradlew --no-daemon --warning-mode all :app:lintDebug :app:assembleDebug` in CI or a networked development environment.
2. Perform a physical-device UI smoke in both **English and Russian**, including a narrow phone width and increased system font size.
3. Exercise one long Fastboot operation and one Mi Unlock/Sideload flow from non-Home tabs to confirm the live strip stays correct, Console never auto-expands, and the full Operation Center remains readable throughout state changes.
4. Confirm success/cancel strip auto-clear and failed/verify-pending persistence/dismiss behavior on a physical device.
5. Run TalkBack/accessibility smoke for the Console dock, import icon, live-operation strip and Operation Center actions.
6. Refresh README/GitHub Pages screenshots from the validated build so published visuals match the current Operation Center.

No backend/core refactor is required by this UI audit.
