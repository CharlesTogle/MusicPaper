# Contributing to MusicPaper

Thanks for wanting to help! Bug reports, feature ideas, and pull requests are all welcome.

## Reporting a bug

Open an [issue](https://github.com/CharlesTogle/MusicPaper/issues) and include:

- Your phone model and Android version
- The music app you were using (Spotify, YouTube Music, a local player, ...)
- What you expected to happen and what happened instead
- Logs if you have them: `adb logcat -s MusicPaper`

## Suggesting a feature

Open an issue describing the problem you want solved before writing a lot of code,
so we can agree on the approach first.

## Sending a pull request

1. Fork the repo and create a branch from `main` (`git checkout -b fix/short-description`).
2. Make your change. Keep pull requests small and focused on one thing; they get reviewed faster.
3. Check that it builds: `./gradlew assembleDebug`
4. Test it on a real device or emulator, including the lock screen.
5. Open the pull request against `main` and describe what changed and how you tested it.
   Screenshots help a lot for anything visual.

## Code style

- Kotlin, following the [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Keep classes small and single-purpose, like the existing files in `app/src/main/java/dev/musicpaper/`.
- Don't log song titles, artists, or other personal data in release builds; guard it with `BuildConfig.DEBUG`.
- No new permissions or network calls without discussing it in an issue first. MusicPaper's promise is
  that nothing leaves the phone.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
