# CLAUDE.md — MirrorMsg (Android)

This repository is **public**. Its working rules live in the private
MirrorMsg watch repository's CLAUDE.md ("Phone app" and the sections around
it): read that first, every time. Issues about this app are filed there,
never here.

Here, always:

- Nothing private: no keys, tokens, access codes, message text, contact
  names, numbers, account names or anyone's data, in code, comments,
  commits, tests or PR text.
- Work on `claude/` branches and open pull requests; never push to `main`.
- Keep PR descriptions short and technical. Test steps and discussion go in
  the issue in the private repository.
- `./gradlew :app:assembleDebug` builds the debug app.

## Build

- `.github/workflows/build.yml` builds the Go bridges (`combined-go/bind.sh`,
  cached until the Go code changes) and the debug APK on every push to
  `main`, `testing` and `claude/` branches, and runs the unit tests. It is a
  compile check: it publishes nothing. Errors show as check-run annotations.
- Every package the app imports as `fi.mirrormsg.<name>` must be listed in
  `combined-go/bind.sh`.
- Branches: `main` and `testing` (changes waiting to be tried, on top of
  main). `sync-testing.yml` merges main into testing whenever main moves.
- Don't bump `versionCode` or `versionName`, and don't touch release
  signing or `keystore.properties`.
