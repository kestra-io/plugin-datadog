# Root documentation size

The 30000-byte cap came from `scripts/assemble-docs.py`. It is not a limit in the checked Kestra documentation code.

Evidence inspected on 2026-11-05:

- [Gradle plugin v1.2.12 DocRules](https://github.com/kestra-io/gradle-plugin/blob/v1.2.12/plugin-doc-lint/src/main/groovy/io/kestra/gradle/rules/DocRules.groovy) checks that the root doc exists, referenced Markdown exists and each doc has at least ten lines. It has no byte maximum.
- [Kestra docs plugin loader](https://github.com/kestra-io/docs/blob/main/src/loaders/plugins.ts) accepts `longDescription` and `description` as optional strings without a length maximum.
- [Plugin page blocks](https://github.com/kestra-io/docs/blob/main/src/utils/plugins/pageBlocks.ts) builds page data and navigation without a byte maximum.

The assembly default is now 40000 bytes. This is a local editorial budget. The root can be checked without the previous `--max-bytes 80000` override. Full area documentation stays in `docs/areas/`.

`assemble-docs.py --check` measured 34938 root-document bytes and 116 component rows. The integrated Gradle `check`, including `lintPluginDocs`, passed with that root document.

These source checks do not prove that every downstream website service accepts arbitrary document sizes. The Kestra website was not built locally.
