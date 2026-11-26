# AI Contribution Policy

AI-assisted pull requests are welcome in Picsou. The maintainers use coding agents themselves; what matters is small, reviewable, well-tested work.

## Rules

1. **You own every line you submit.** You must read, understand, and be able to explain every change in your pull request, and answer review questions in your own words.
2. **Disclose AI use.** State in the pull request description when AI tools or coding agents were used (for example in the **Self-review** and **Design decisions** sections of the pull request template).
3. **Agree on features first.** If you do not have previously merged pull requests in this repository, open an issue and get agreement from a maintainer **before** opening a feature pull request (including new bank connectors). Returning contributors should also discuss non-trivial features first — see [`CONTRIBUTING.md`](CONTRIBUTING.md#before-you-open-a-pull-request).
4. **Low-effort AI PRs may be closed without review.** Maintainers have limited review time and may close unreviewed, unverified, oversized, or low-effort AI-generated pull requests without a detailed review. Run the local tests and a self-review loop before marking a pull request ready for review.
5. **No real bank or personal data — ever.** The bank-connector privacy rules in [`CONTRIBUTING.md`](CONTRIBUTING.md#before-you-open-a-pull-request) apply equally to AI-assisted work: use synthetic fixtures only, and never commit or paste real credentials, account numbers, IBANs, balances, session tokens, cookies, HAR files, logs, or screenshots with personal data anywhere in code, tests, fixtures, issues, or pull requests.
