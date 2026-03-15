# Contributing

Thanks for contributing to Svate.

## Before You Start

- Keep secrets out of the repository. Use local `.env.local` files or your secret manager.
- Do not commit device-specific, IDE-specific, or proxy/network profile files.
- Keep changes scoped. Separate bug fixes, refactors, and formatting cleanup when possible.

## Development Setup

Server:

```bash
cd software-nav-assistant
npm ci
npm run lint
npm test
npm run build
```

Android:

```bash
./gradlew.bat test
./gradlew.bat :app:assembleDebug
```

See `docs/build-matrix.md` for the validated toolchain baseline.

## Pull Requests

- Describe the user-visible effect and the technical change.
- Mention any environment variables, migrations, or deployment changes.
- Include screenshots or logs when changing UI flows, agent actions, or API behavior.
- Avoid unrelated file churn in the same PR.

## Code Style

- Follow the existing project structure and naming patterns.
- Keep API changes backward compatible unless the change is explicitly planned as breaking.
- Add or update tests for auth, persistence, routing, or safety-sensitive logic.
- Prefer UTF-8 text and keep comments readable and intentional.

## Security

- Treat screenshots, telemetry, tokens, and database URLs as sensitive.
- If you find a secret or unsafe default in the repository, open a security-focused PR and remove it from history when required.
