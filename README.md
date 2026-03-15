# Svate

Svate is an Android Claw: a mobile task assistant for Android that can observe the UI, capture the screen, and execute actions on-device.

It combines:

- an Android client that observes the UI, captures screenshots, and executes actions through Accessibility and MediaProjection
- a Next.js service that validates requests, plans the next step, stores telemetry, and issues signed upload URLs

## Repository Layout

- `app/`: Android client
- `software-nav-assistant/`: web and cloud service
- `docs/`: project notes and build docs

## What It Does

- reads UI structure and screen state from the Android device
- sends structured observations to the server for planning and review
- executes safe actions such as tap, input, scroll, back, and app launch
- uploads screenshots through signed GCS URLs instead of inlining large payloads
- records telemetry and turn-level execution data for debugging and evaluation

## Development

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

See [docs/build-matrix.md](docs/build-matrix.md) for the validated toolchain baseline.

## Security

- do not commit `.env.local` or other local secret files
- configure API keys, database URLs, and internal tokens through environment variables or your secret manager
- this repository only keeps `.env.example` as a template

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for development workflow, validation commands, and PR expectations.

## License

This repository uses the license in [LICENSE](LICENSE).
