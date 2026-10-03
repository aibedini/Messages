<!-- OPENWIKI:START -->

## OpenWiki

This repository has a generated `openwiki/` evidence index. It is optional just-in-time context, not required startup reading.

- Treat source code and tests as authoritative. A brief's unknowns and review items are verification gaps, not automatic requirements.
- Prefer the narrowest quiet validation that proves the changed behavior. Preserve complete failure output.

The scheduled OpenWiki GitHub Actions workflow refreshes the repository wiki. Do not hand-edit generated OpenWiki pages unless explicitly asked; prefer updating source code/docs and letting OpenWiki regenerate.

<!-- OPENWIKI:END -->

<!-- BEGIN MANAGED: SPEC_KIT_REPOSITORY_POLICY -->

## Spec-Driven Development (Spec Kit)

This repository is initialized with GitHub Spec Kit **1.0.6** through the `dsh`
integration. The binding engineering constitution is
`.specify/memory/constitution.md`; it encodes the modem-boundary, final
validation, durable dedupe, ACK-semantics and Room-migration invariants that
previous production incidents violated. Where convenience and the constitution
disagree, the constitution wins.

- Skills live in `.dsh/skills/speckit-*/` and are invoked in DSH as
  `/speckit-specify`, `/speckit-plan`, `/speckit-bug-assess`, and so on.
- Health: `specify integration status --json` (expect `"status": "ok"`).
- Artifacts: features in `specs/<NNN-slug>/`, bug reports in
  `.specify/bugs/<slug>/`, idea assessments in `.specify/assessments/<slug>/`.
- After an approved Spec Kit upgrade: `specify integration upgrade dsh` and
  review the generated diff before continuing application work.

### Change classification

| Request | Workflow |
| --- | --- |
| Typo, comment, label, colour, version bump | No Spec Kit ceremony. Every rule below still applies. |
| Non-trivial bug | `/speckit-bug-assess` then `/speckit-bug-fix` then `/speckit-bug-test` |
| Feature, refactor, schema, gateway-protocol, or outbound-send change | `/speckit-specify` then `/speckit-clarify` (when ambiguous) then `/speckit-plan` then `/speckit-tasks` then `/speckit-analyze` then `/speckit-implement` then `/speckit-converge` |
| Uncertain architecture idea | `/speckit-assess-intake` then `/speckit-assess-research` then `/speckit-assess-define` then `/speckit-assess-shape` then `/speckit-assess-decide` |

Do not begin `/speckit-implement` before `/speckit-analyze` reports the
artifacts are coherent enough to proceed. Repeat implement/converge until the
result is CONVERGED.

### Cross-repository work

Messages Android performs the irreversible physical SMS submission; GMweb owns
the gateway contract and durable revocation state; EVE decides *why* a
notification should exist. The app is a **consumer** of the GMweb gateway
contract and must not independently invent gateway state names or semantics. A
change touching more than one of those repositories must carry one shared
feature ID (for example `stale-sms-revocation-v4`) through its spec, plan, ADR
references and acceptance report, and must follow the Cross-Repository Contract
section of the constitution.

`protocol/pairing-protocol-v1.json` is byte-compared against
`GMweb-API/shared/pairing-protocol-v1.json` in CI and must never drift
independently.

### Evidence commands

JDK 17+ is required; CI uses Temurin 17. On this machine set
`JAVA_HOME` to the JDK under `.build-tools\jdk21` before invoking the wrapper.

- JVM unit tests: `./gradlew testDebugUnitTest`
- Compile only (fast): `./gradlew :app:compileDebugKotlin`
- Instrumented / device-level acceptance: `./gradlew connectedDebugAndroidTest`
- Lint: `./gradlew lintDebug`
- Debug build (mirrors CI's build-debug job): `./gradlew assembleDebug`

Safety-critical outbound-flow work additionally requires the pull, dedupe,
pre-send validation, superseded-result, submit-boundary, ACK, retry, restart,
reconnect and late-revocation tests described in the constitution. Where
behavior depends on the Android framework or `SmsManager`, JVM tests alone are
not acceptance.

<!-- END MANAGED: SPEC_KIT_REPOSITORY_POLICY -->

