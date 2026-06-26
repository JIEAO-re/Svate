# Svate

[![CI](https://img.shields.io/github/actions/workflow/status/JIEAO-re/Svate/mobile-agent-ci.yml?branch=main&label=ci)](https://github.com/JIEAO-re/Svate/actions/workflows/mobile-agent-ci.yml)
[![License](https://img.shields.io/github/license/JIEAO-re/Svate)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android-3ddc84)](https://developer.android.com/)

Svate is an Android Claw for on-device UI automation.

It combines an Android runtime that can inspect the UI, capture the screen, and execute actions through Accessibility and MediaProjection, with a Next.js backend that plans steps, reviews risk, stores telemetry, and issues signed upload URLs.

## Why It Is Interesting

- It is not a screenshot-only toy. The Android side keeps UI tree access, text-based actions, SoM markers, and coordinate-driven execution paths together.
- It is designed as an agent runtime, not just a demo script. The device owns a Claude-Code-style turn loop: a tool registry the model drives via function calling, a risk-based permission gate, and on-screen prompt-injection defense.
- It already includes practical engineering pieces that matter in real runs: screenshot transport via signed GCS uploads, telemetry persistence, auth guards, and regression tests.

## Core Capabilities

- Read current UI structure from Accessibility.
- Capture screenshot frames through MediaProjection.
- Execute taps, typing, scrolling, back, home, app launch, and direct intent paths.
- Search the web directly via a `web_search` tool (Tavily / Brave / self-hosted SearXNG), so the agent can look things up without driving a browser app.
- Use Spatial Grounding, SoM markers, selectors, and legacy bbox fallback as targeting strategies.
- Upload screenshots through signed GCS URLs instead of inlining large payloads by default.
- Run safety checks before execution, including high-risk action interception and visual injection defense.
- Persist telemetry, turn data, and session state for debugging and evaluation.

## Architecture

Android app (on-device agent loop):

- `AgentLoop`: the turn loop. Each turn rebuilds the system prompt from the device's real-time capabilities, calls the model with function calling, executes the chosen tool locally, and feeds a fresh observation back.
- `ToolRegistry` / `PhoneTool`: the tool set the model can call — screenshot, UI-tree read, tap/type/scroll/back/home, app launch, intents, files, `web_search`, and privileged Shizuku admin tools.
- `PermissionGate`: per-action risk gating (SAFE/LOW/NORMAL/HIGH risk × ASK/AUTO/SAFE/EXPERIMENTAL modes) with a deny floor and keyword interception.
- Observation & defense: `UiTreeParser` + `UiNodePruner` (UI tree), `AgentCaptureService` (MediaProjection frames), and `VisualInjectionGuard` (on-screen prompt-injection screening).

The model can be called either device-direct (the device talks straight to an OpenAI-compatible endpoint) or through the Next.js backend, which acts as a model proxy. Tools always execute on-device, so they work in both modes.

Server:

- Next.js API routes for auth, planning, telemetry, and signed upload URLs.
- Zod contracts for request and response validation.
- Persistence for turns, telemetry, live metrics, and media jobs.
- Internal job routes and cloud deployment support.

## Repository Layout

- `app/`: Android client and agent runtime.
- `software-nav-assistant/`: web and cloud service.
- `docs/`: build notes and project docs.

## Getting Started

Server:

```bash
cd software-nav-assistant
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

Android:

```bash
./gradlew.bat test
./gradlew.bat :app:assembleDebug
```

See [docs/build-matrix.md](docs/build-matrix.md) for the validated toolchain baseline.

## Current State

- The repo is open source and publicly buildable.
- The Android side is intended for advanced automation experiments and agent infrastructure work.
- Expect to provide your own environment variables, cloud resources, and Android permissions setup before running end to end.

## Security

- Do not commit `.env.local` or other local secret files.
- Configure API keys, database URLs, and internal tokens through environment variables or a secret manager.
- This repository keeps `.env.example` only as a template.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development workflow, validation commands, and PR expectations.

## License

This repository uses the [MIT License](LICENSE).
