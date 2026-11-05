# Security Policy

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

Instead, report them privately using GitHub's [Security tab](https://github.com/sparrowwallet/frigate/security/advisories/new) for this repository. This creates a private security advisory visible only to you and the maintainers.

If you are unable to use GitHub, you may email mail@sparrowwallet.com. Sensitive reports can be encrypted to [craigraw's GPG key](https://keybase.io/craigraw) (fingerprint `D4D0D3202FC06849A257B38DE94618334C674B40`).

### What to include

To help triage the report quickly, please include as much of the following as you can:

- The Frigate version, operating system, and how Frigate was installed (package, installer, or built from source)
- A description of the vulnerability and its impact
- Steps to reproduce, ideally with a proof of concept
- Any relevant configuration (network, `config.toml` settings, compute backend and GPU, Bitcoin Core version, backend Electrum server, and whether Frigate is behind a reverse proxy or Tor)

Please do not include scan private keys, seed words, or wallet passwords for wallets holding real funds in a report. If keys are necessary to reproduce, generate a fresh wallet, or use signet or regtest.

### What to expect

- An acknowledgement of your report, usually within a few days
- An assessment of the issue
- Updates on the advisory as the fix progresses

Fixes for serious issues are generally released as soon as they are ready, rather than being held for a scheduled release.

### Disclosure

Frigate follows the [Bitcoin Core Security Disclosure Policy](https://bitcoincore.org/en/security-advisories/). Reports are assigned one of four severity levels, which determines when details are made public:

| Severity | Description | Disclosure |
| --- | --- | --- |
| **Low** | Hard to exploit, with minor impact. Typically requires a non-default configuration or local access to the server. | No advisory, disclosed in the release notes |
| **Medium** | Limited in scope, or requiring special conditions to trigger. | 1 year after a release containing the fix |
| **High** | Significant impact, and typically exploitable under a default configuration. | 1 year after a release containing the fix |
| **Critical** | Threatens user funds or privacy at scale, such as remote code execution on, or remote extraction of client keys from, public servers. | Ad hoc |

The delay before disclosing Medium and High severity issues exists to give operators running older versions time to upgrade. Critical issues fall outside the standard policy, as they are likely to require an ad hoc process worked out at the time.

Severity is assigned by the maintainers. Reports that do not show what an attacker gains, and what access they need to gain it, are handled as ordinary bugs, through the public issue tracker where appropriate. These are not assigned a severity and no advisory is published. Being accepted for triage does not by itself mean an advisory will follow.

For Medium and High severity issues, the advisory is published once the disclosure period has elapsed. Credit is given to the reporter, in the advisory or the release notes, unless anonymity is preferred.

Please give us the opportunity to release a fix before disclosing the issue publicly. If you intend to disclose on a timeline of your own, say so in your report so it can be discussed early.

There is currently no bug bounty programme for Frigate.

## Supported Versions

Security fixes are made against the latest release only. Operators are encouraged to run the most recent version, available from the [GitHub releases](https://github.com/sparrowwallet/frigate/releases) page.

Release binaries are signed with the GPG key above. Verifying the signature on a download before installing is strongly recommended.

## Scope

This policy covers Frigate, its submodule [Drongo](https://github.com/sparrowwallet/drongo) (Bitcoin protocol and wallet primitives), and the [DuckDB ufsecp extension](https://github.com/sparrowwallet/duckdb-ufsecp-extension) bundled with it (in-database secp256k1 computation). Report issues in any of them here rather than on the other repositories, so that a fix and a Frigate release can be coordinated.

Examples of issues we are particularly interested in:

- Disclosure of a client's scan private key, whether to other clients, to the network, or by persisting it to disk or logs
- Leaks between sessions, such as one client receiving another client's subscriptions, scan results or notifications
- Privacy leaks that link a client's addresses or silent payments to their identity or network address beyond what is inherent to using a server
- Remote code execution or memory corruption, including via crafted JSON-RPC requests, a malicious or compromised backend Electrum server, or the GPU and DuckDB extension code paths
- Incorrect scan results that would cause a wallet to miss payments it has received, or to report payments it has not
- Denial of service against a server with the default limits, where a single client can exhaust memory, CPU, GPU or connections far beyond what the [limits](README.md#limits) are meant to allow
- Weaknesses in TLS handling, or the admin port being reachable from anywhere but the loopback address

The following are generally out of scope:

- Vulnerabilities in Bitcoin Core, backend Electrum servers, GPU drivers, or upstream dependencies, which should be reported to the relevant project (though we appreciate being told where Frigate's use of them makes the impact worse)
- Attacks requiring a compromised Bitcoin Core node, which Frigate trusts as its source of chain data
- Attacks requiring an already compromised operating system, or access to the server's account or data directory
- Consequences of operator configuration choices, such as exposing the plaintext listener publicly, or adding untrusted subnets to `excludedSubnets`
- Volumetric denial of service that the [limits](README.md#limits) are not designed to prevent, such as many clients from many IP addresses
- Missing hardening or best-practice recommendations without a demonstrated impact
- Reports generated by automated scanners or language models where the reporter has not independently verified the issue and demonstrated its impact. A plausible-sounding description of a vulnerability is not a vulnerability; if you cannot reproduce it, we are unlikely to be able to either
