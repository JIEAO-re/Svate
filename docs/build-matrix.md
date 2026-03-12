# Build Matrix

This repository is validated with the following baseline:

- Node.js 20 for `software-nav-assistant`
- npm from Node.js 20
- JDK 17 for Android and Gradle

Validation commands:

- `cmd /c npm run lint` from `software-nav-assistant`
- `cmd /c npm test` from `software-nav-assistant`
- `./gradlew.bat test` from the repository root
- `./gradlew.bat :app:assembleDebug` from the repository root
