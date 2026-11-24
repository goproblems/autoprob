# Project Guidelines

- Do not add, modify, or run automated tests unless the user explicitly requests it.
- Do not add test files for feature implementations or bug fixes, or run test commands automatically.
- Compilation, packaging, and manual verification are allowed as needed. Use `-Dmaven.test.skip=true` for Maven builds to skip test compilation and execution.
- The problem validator is the exception: for changes to `autoprob/validate` (redo, missing variations, the crawl and their review pages) and to the Go board and sgf code it relies on (`autoprob/go`), keep the unit tests up to date and run them with `mvn -q test`. They take about 90 seconds, need no engine or network, and are the only quick check the validator's judging rules have.
