![Frigate logo](https://github.com/sparrowwallet/frigate/raw/refs/heads/master/frigatelogo.png)

# Frigate Electrum Server

Frigate is an Electrum server for [Silent Payments](https://github.com/bitcoin/bips/blob/master/bip-0352.mediawiki) (BIP352).
It performs Silent Payments scanning server-side using ephemeral client keys, returning discovered transactions to clients over an extension to the Electrum JSON-RPC protocol.
Scanning runs in-database with optional GPU acceleration, supporting both single-user instances on commodity hardware and multi-user public servers.

## Features

- Server-side Silent Payments scanning with ephemeral (in-RAM) client keys, following the [Remote Scanner](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md#remote-scanner-ephemeral) approach in the BIP352 Index Server Specification.
- GPU-accelerated scanning with CUDA (NVIDIA), Metal (Apple), and OpenCL (Intel/AMD/NVIDIA) backends, selected automatically at runtime.
- In-database EC point computation via a custom [DuckDB extension](https://github.com/sparrowwallet/duckdb-ufsecp-extension) wrapping [UltrafastSecp256k1](https://github.com/shrec/UltrafastSecp256k1).
- Low-latency mempool ingestion via Bitcoin Core's ZMQ `sequence` publisher.

## Quick Start

1. Install Bitcoin Core 28 or higher with `txindex=1` in `bitcoin.conf`. (28+ is required for Electrum protocol 1.6; earlier versions work for 1.3–1.5.)
2. Download the latest Frigate release for your platform from the [Releases](https://github.com/sparrowwallet/frigate/releases) page (`.deb` / `.rpm` for Linux, `.dmg` for macOS, `.zip` for Windows). Releases are signed — see [GPG Key](#gpg-key).
3. Start Frigate:
   ```shell
   /opt/frigate/bin/frigate                            # Linux .deb / .rpm
   /Applications/Frigate.app/Contents/MacOS/Frigate    # macOS
   ```
4. With Bitcoin Core on the same host using default settings, no further configuration is required. Frigate creates `~/.frigate/config.toml` on first start.
5. Frigate indexes from Taproot activation (mainnet) up to the chain tip before serving Silent Payments queries. Watch the log for `Electrum server listening on tcp://...` for the readiness signal.
6. (Optional) Verify with the bundled CLI:
   ```shell
   /opt/frigate/bin/frigate-cli
   ```

For non-mainnet networks, pass `-n testnet|testnet4|signet|regtest`.

## Configuration

Frigate stores its configuration in `~/.frigate/config.toml` on macOS and Linux, and `%APPDATA%\Frigate\config.toml` on Windows.
A default configuration file is created on first startup. For indexing, Frigate needs access to the Bitcoin Core RPC, which requires `txindex=1` in `bitcoin.conf`.

With Bitcoin Core running on the same machine with default settings, Frigate will connect automatically with no configuration changes required.

```toml
# Frigate configuration

[core]
connect = true
# server = "http://127.0.0.1:8332"
# authType = "COOKIE"            # COOKIE or USERPASS
# dataDir = "/home/bitcoin/.bitcoin"
# auth = "user:password"         # only needed for USERPASS
# zmqSequenceEndpoint = "tcp://127.0.0.1:28336"   # bitcoind -zmqpubsequence endpoint for low-latency mempool ingestion
# rpcRequestTimeoutSeconds = 60                   # per-RPC read timeout (raise on slow/remote bitcoind, lower on fast LAN)
# rpcBatchSize = 100                              # max sub-requests per JSON-RPC array batch (mempool fill)

[index]
# startHeight = 0                # default: 709632 on mainnet (Taproot activation), 0 on testnet
# cacheSize = "10M"              # scriptPubKey cache entries (default: 10M, ~4GB RAM)

[scan]
# batchSize = 300000             # rows per GPU dispatch (reduce if scanning hangs on older GPUs)
# computeBackend = "AUTO"        # AUTO, GPU, or CPU
# dbThreads = 4                  # limit DuckDB threads (reduces CPU load when computeBackend = "CPU")
# memoryLimit = "8GB"            # cap DuckDB memory usage (default: 80% of system RAM)
# maxLabels = 10                 # maximum number of labels accepted per silent payments subscription
# maxSubscriptions = 100         # maximum number of silent payments subscriptions per connection

[server]
# host = "localhost"             # advertised in server.features (set to public hostname for public-facing deployments)
# tcp = "tcp://0.0.0.0:50001"    # plaintext listener bind URL; omit (or "") to disable. Default if neither tcp nor ssl is set.
# ssl = "ssl://0.0.0.0:50002"    # SSL listener bind URL; omit to disable
# sslCert = "cert.pem"           # PEM certificate (chain allowed); bare filename resolves under Frigate's home dir, or use an absolute path
# sslKey  = "key.pem"            # PEM-encoded PKCS#8 private key; bare filename resolves under Frigate's home dir, or use an absolute path
# backendElectrumServer = "tcp://localhost:60001"   # backend must listen on a port distinct from Frigate's tcp/ssl listeners above
```

### Core

Set `connect = false` to run Frigate without connecting to Bitcoin Core.
This is useful if an index has already been built and you just want to serve queries against it.
The `authType` can be `COOKIE` (default) or `USERPASS`.
For cookie authentication, set `dataDir` to the Bitcoin Core data directory if it is not in the default location.
For user/password authentication, set `auth` to `user:password`.

When `zmqSequenceEndpoint` is set, Frigate subscribes to Bitcoin Core's ZMQ `sequence` publisher for low-latency mempool ingestion (sub-100ms instead of up to 5s).
This requires Bitcoin Core to be started with `-zmqpubsequence=tcp://...:<port>` matching this endpoint, and a Bitcoin Core build with ZMQ support compiled in.
Release binaries from bitcoincore.org include ZMQ support; a from-source build needs the ZeroMQ development library (e.g. `libzmq3-dev`) and an explicit `-DWITH_ZMQ=ON`, since CMake silently disables ZMQ if the library is not found.
Verify support with `bitcoin-cli getzmqnotifications` — a `-32601 Method not found` error means the binary has no ZMQ support.

**Configuring `zmqSequenceEndpoint` is strongly recommended whenever `backendElectrumServer` is configured.**
Without this configured, the backend may notify the client of a new transaction via scripthash before Frigate's silent payments notification lands — causing wallets to briefly display incorrect amounts.

The `rpcRequestTimeoutSeconds` setting controls the per-RPC read timeout against Bitcoin Core (default: 60).
Raise it for a slow or remote node if you see read timeouts in the log; the default is fine for a co-located Bitcoin Core.
The `rpcBatchSize` setting caps the number of sub-requests per JSON-RPC array batch used during initial mempool indexing (default: 100); the default suits most deployments.

### Index

Indexing speed is greatly affected by looking up the scriptPubKeys of spent outputs.
To improve performance, scriptPubKeys are cached to avoid looking them up again with `getrawtransaction`.
The `cacheSize` limits the number of scriptPubKeys cached during indexing (e.g. `"10M"` for 10 million entries, ~4GB RAM).
This value can be increased or decreased depending on available RAM.

The DuckDB database is stored in a `db` subfolder in the same directory, in a file called `frigate.duckdb`.
DuckDB databases can be transferred between different operating systems, and should survive unclean shutdowns.

### Scan

The `computeBackend` setting controls whether historical scanning uses GPU or CPU. Valid values are `AUTO` (default), `GPU`, and `CPU`.
In `AUTO` mode, the GPU is used if one is detected, otherwise the CPU is used.
Set to `CPU` to force CPU-only scanning.
With CPU-only scanning, `dbThreads` can be used to limit the number of DuckDB threads and reduce CPU load.
The `memoryLimit` setting caps DuckDB's memory usage (e.g. `"8GB"`, `"1024MB"`). DuckDB's default is 80% of system RAM.
Mempool and incremental block scans always run on the CPU backend, since they are short, latency-sensitive and benefit from being decoupled from the longer-running historical scans.

The `batchSize` setting controls how many transactions are processed per GPU dispatch (default: 300,000).
If scanning hangs or becomes unstable on certain GPUs (particularly older OpenCL-only GPUs), try reducing this value (e.g. 10,000 to 50,000).

The `maxLabels` setting caps the number of labels accepted per silent payments subscription (default: 10).
The `maxSubscriptions` setting caps the number of silent payments subscriptions per connection (default: 100).
Requests exceeding either limit are rejected with a JSON-RPC `-32602 Invalid params` error.

### Server

Listeners are configured as bind URLs in the scheme `tcp://` or `ssl://` followed by a host and port.
The `tcp` setting is the plaintext listener (default: `tcp://0.0.0.0:50001`), and `ssl` enables a TLS listener (e.g. `ssl://0.0.0.0:50002`).
Each URL specifies the bind interface — use `127.0.0.1` to restrict a listener to localhost, or `0.0.0.0` to bind all interfaces.
Both listeners can run simultaneously, or either can be omitted (e.g. set `tcp = ""` to run TLS only).
If neither `tcp` nor `ssl` is set, Frigate defaults to tcp on `0.0.0.0:50001`.

To enable TLS, set `ssl` and supply a certificate and private key via `sslCert` and `sslKey`.
Bare filenames are resolved relative to Frigate's home directory (the per-network directory that holds `config.toml`). Absolute paths are used as-is.
The certificate file may contain a single certificate or a full chain (`fullchain.pem`), and the key file must be an unencrypted PKCS#8 (`-----BEGIN PRIVATE KEY-----`) PEM.
TLS 1.0, 1.1 and SSLv3 are unconditionally disabled as insecure, and only TLS 1.2 and 1.3 are negotiated.

To generate a self-signed certificate for local or testing use, valid for ten years:
```shell
openssl req -x509 -newkey rsa:2048 -keyout ~/.frigate/key.pem -out ~/.frigate/cert.pem -days 3650 -nodes -subj "/CN=localhost"
```

Frigate implements the Silent Payments RPCs natively and proxies all other Electrum requests (including address-related lookups) to a co-located Electrum backend.
The backend is configured with `backendElectrumServer`, and is intended to point to a server running on the same host.
Because Frigate occupies the canonical 50001/50002 Electrum ports, a co-located backend (Fulcrum, ElectrumX, electrs, etc.) must be configured to listen on a different port.
The Electrum protocol from 1.3 to 1.6 is supported — for 1.6, ensure Bitcoin Core 28 or higher.

When `backendElectrumServer` is set, also configure `zmqSequenceEndpoint` under `[core]` — see the [Core](#core) section above for why this pairing matters.

## Usage

The Frigate server may be started as follows:
```shell
./bin/frigate
```

or on macOS:
```shell
./Frigate.app/Contents/MacOS/Frigate
```

To start with a different network, use the `-n` parameter:
```shell
./bin/frigate -n signet
```

To change the home directory for config and data storage (default: `~/.frigate/` on Linux/macOS, `%APPDATA%\Frigate` on Windows), use the `-d` parameter:
```shell
./bin/frigate -d /var/lib/frigate
```

The full range of options can be queried with:
```shell
./bin/frigate -h
```

### Frigate CLI

Frigate also ships a CLI tool called `frigate-cli` to allow easy access to the Electrum RPC.
```shell
./bin/frigate-cli
```

or on macOS:
```shell
./Frigate.app/Contents/MacOS/frigate-cli
```

It uses similar arguments, for example:
```shell
./bin/frigate-cli -n signet
```

The scan private key and spend public key, along with the start block height or timestamp, can be specified as arguments or are prompted for:
```shell
./bin/frigate-cli -s SCAN_PRIVATE_KEY -S SPEND_PUBLIC_KEY -b 890000
```

```shell
./bin/frigate-cli
Enter scan private key: SCAN_PRIVATE_KEY
Enter spend public key: SPEND_PUBLIC_KEY
Enter start block height or timestamp (optional, press Enter to skip): 890000
```

By default the CLI client closes once the initial scan is complete, but it can be configured to `follow` or stay open for incoming updates.
When in follow mode, results are only printed if transactions are found.
```shell
./bin/frigate-cli -f
```

The full range of options can be queried with:
```shell
./bin/frigate-cli -h
```

## Deployment

### Recommended Topology

The expected production deployment is a single host running Bitcoin Core, Frigate, and a co-located Electrum backend (Fulcrum, electrs, or ElectrumX):

```
                 ┌─────────────────────────────────────────────┐
client ─TLS/TCP─►│  Frigate :50001 / :50002                    │
                 │    │                                        │
                 │    ├─ Silent Payments RPCs (native)         │
                 │    └─ all other RPCs ─► Electrum backend    │
                 │                          :60001 (localhost) │
                 │                            │                │
                 │                            ▼                │
                 │                         Bitcoin Core        │
                 │                          :8332 RPC          │
                 │                          :28336 ZMQ         │
                 └─────────────────────────────────────────────┘
```

Frigate occupies the canonical Electrum ports (50001/50002). 
The backend listens on a non-conflicting port (e.g. 60001) bound to localhost, and all non-silent-payments queries are proxied to it transparently.

### Backend Limits

All Frigate client connections to `backendElectrumServer` appear from a single source IP, so backend per-IP caps (e.g. Fulcrum's `max_clients_per_ip`) can refuse new Frigate clients well below the backend's overall capacity.
The co-located localhost topology above is handled by default: Fulcrum exempts `127.0.0.1/32` and `::1/128`, and ElectrumX exempts all private addresses (RFC1918, loopback, link-local).
If Frigate runs on a different host from the backend, on Fulcrum add Frigate's IP to `subnets_to_exclude_from_per_ip_limits`.
On ElectrumX the exemption set is hardcoded to private addresses, so keep Frigate on the same private network.
electrs imposes no per-IP limits and requires no tuning.

### Running as a Service

Frigate runs as a long-lived foreground process and integrates straightforwardly with `systemd`. A minimal unit (`/etc/systemd/system/frigate.service`):

```ini
[Unit]
Description=Frigate Electrum Server
After=network-online.target bitcoind.service
Wants=network-online.target

[Service]
Type=simple
User=frigate
StateDirectory=frigate
ExecStart=/opt/frigate/bin/frigate -d /var/lib/frigate
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

`StateDirectory=frigate` ensures `/var/lib/frigate` exists and is owned by the service user, so the dedicated `frigate` account does not need a real home directory (`useradd --system --no-create-home frigate`). Adjust `User=` and the binary path to match your install.

### Public-facing TLS

For public deployments, supply a real certificate via `sslCert` and `sslKey` rather than the self-signed example in [Configuration > Server](#server).
The `host` field under `[server]` is advertised in `server.features` and should match the hostname clients use when validating the certificate.
TLS termination at a reverse proxy is also supported – in that case, run Frigate with `tcp = "tcp://127.0.0.1:50001"` and terminate TLS upstream.

### Resource Requirements

- **RAM**: indexing uses ~4 GB for the default scriptPubKey cache (`cacheSize = "10M"`). Serving steady-state queries is dominated by DuckDB's memory pool, which defaults to 80% of system RAM and can be capped via `memoryLimit`. 16 GB is a comfortable baseline; 8 GB works with a reduced cache and memory limit.
- **Disk**: ~18 GB for the DuckDB index, growing slowly over time.
- **CPU**: any modern x86-64 or ARM64 server. Indexing is I/O- and RPC-bound rather than CPU-bound.
- **GPU**: optional but strongly recommended for multi-user instances. A single discrete GPU (NVIDIA Ampere or newer, Apple Silicon, or any OpenCL 1.2+ device including integrated GPUs) is sufficient for several simultaneous scans. See [GPU Requirements](#gpu-requirements).

## Operations

### Initial Sync

On first start Frigate indexes from Taproot activation (block 709,632 on mainnet) up to the chain tip. 
It does not begin serving Silent Payments queries until the index reaches the tip — at that point it logs `Electrum server listening on tcp://...`, which is the readiness signal. 
Indexing time depends on RPC throughput from Bitcoin Core.

### Logs

Logs are written to `frigate.log` in the per-network data directory (`~/.frigate/` for mainnet, `~/.frigate/<network>/` otherwise) and to stdout. 
The default appender does not rotate; in production, configure `logrotate` or run under a service manager that captures stdout (e.g. `systemd-journald`).

### Backup

The tweak index can be rebuilt from Bitcoin Core at any time, but a rebuild from Taproot activation is slow. To minimise downtime, snapshot `db/frigate.duckdb` under the data directory while Frigate is stopped (DuckDB files are portable across operating systems and survive unclean shutdowns). 
The `config.toml` and any TLS material in the data directory are worth including in the same backup.

### Upgrades

Stop Frigate, install the new release, start. 
The DuckDB file format and the on-disk index schema are stable across Frigate releases.

### Reorgs

Frigate detects chain reorgs via Bitcoin Core and rescans the affected block range automatically. 
Connected silent-payments subscribers are renotified for the rescanned range; clients reconnecting after a disconnect should pass `start = lastSeenHeight - 100` on resubscribe to cover any reorg they missed.

## Performance

### CPU Performance

Without GPU acceleration the scanning query is CPU bound, mostly around EC point multiplication.
[DuckDB parallelizes](https://duckdb.org/docs/stable/guides/performance/how_to_tune_workloads#parallelism-multi-core-processing) the workload based on row groups, with each row group containing 122,880 rows.
It will by default configure itself to use all the available cores on the server it is running.
The behaviour can be configured in the Frigate configuration file (see `dbThreads`).

The following results were produced by the included `benchmark.py` script scanning mainnet to block height 914,000.
Note that a mainnet database indexing from height 800,000 is required to run the benchmark.

M1 MacBook Pro (10 CPUs):

| | Blocks | Start | End | Transactions | Time | Transactions/sec |
|---|--------|-------|-----|--------------|------|------------------|
| 2 hours | 12 | 913988 | 914000 | 8,207 | 244ms | 33,608 |
| 1 day | 144 | 913856 | 914000 | 127,804 | 2s 681ms | 47,675 |
| 1 week | 1008 | 912992 | 914000 | 751,769 | 3s 600ms | 208,843 |
| 2 weeks | 2016 | 911984 | 914000 | 1,709,358 | 11s 128ms | 153,602 |
| 1 month | 4320 | 909680 | 914000 | 4,240,572 | 19s 958ms | 212,470 |
| 3 months | 12960 | 901040 | 914000 | 13,558,435 | 52s 720ms | 257,179 |
| 6 months | 25920 | 888080 | 914000 | 26,103,759 | 1m 34s | 274,804 |
| 1 year | 52560 | 861440 | 914000 | 59,578,156 | 3m 28s | 286,404 |
| 2 years | 105120 | 808880 | 914000 | 132,994,804 | 7m 47s | 284,342 |

Intel Core Ultra 9 285K (24 CPUs):

| | Blocks | Start | End | Transactions | Time | Transactions/sec |
|---|--------|-------|-----|--------------|------|------------------|
| 2 hours | 12 | 913988 | 914000 | 8,207 | 256ms | 32,121 |
| 1 day | 144 | 913856 | 914000 | 127,804 | 1s 591ms | 80,308 |
| 1 week | 1008 | 912992 | 914000 | 751,769 | 3s 19ms | 249,026 |
| 2 weeks | 2016 | 911984 | 914000 | 1,709,358 | 4s 474ms | 382,106 |
| 1 month | 4320 | 909680 | 914000 | 4,240,572 | 11s 7ms | 385,252 |
| 3 months | 12960 | 901040 | 914000 | 13,558,435 | 27s 605ms | 491,151 |
| 6 months | 25920 | 888080 | 914000 | 26,103,759 | 48s 910ms | 533,711 |
| 1 year | 52560 | 861440 | 914000 | 59,578,156 | 1m 44s | 569,123 |
| 2 years | 105120 | 808880 | 914000 | 132,994,804 | 3m 50s | 576,610 |

Higher performance on the longer periods is possible by increasing the number of CPUs.
Multiple clients conducting simultaneous scans slows each scan linearly, since a single scan already saturates all available CPU cores.

### GPU Performance

GPU performance is significantly higher, and as a result is the default compute backend for historical scans.

MacBook M1 Pro (Metal GPU backend):

| | Blocks | Start | End | Transactions | Time | Transactions/sec |
|---|--------|-------|-----|--------------|------|------------------|
| 2 hours | 12 | 913988 | 914000 | 8,207 | 32ms | 259,509 |
| 1 day | 144 | 913856 | 914000 | 127,804 | 240ms | 532,614 |
| 1 week | 1008 | 912992 | 914000 | 751,769 | 1s 313ms | 572,722 |
| 2 weeks | 2016 | 911984 | 914000 | 1,709,358 | 3s 91ms | 552,981 |
| 1 month | 4320 | 909680 | 914000 | 4,240,572 | 7s 458ms | 568,576 |
| 3 months | 12960 | 901040 | 914000 | 13,558,435 | 23s 288ms | 582,196 |
| 6 months | 25920 | 888080 | 914000 | 26,103,759 | 44s 575ms | 585,617 |
| 1 year | 52560 | 861440 | 914000 | 59,578,156 | 1m 41s | 586,138 |
| 2 years | 105120 | 808880 | 914000 | 132,994,804 | 3m 47s | 584,231 |

NVIDIA RTX 5080 (CUDA backend):

| | Blocks | Start | End | Transactions | Time | Transactions/sec |
|---|--------|-------|-----|--------------|------|------------------|
| 2 hours | 12 | 913988 | 914000 | 8,207 | 18ms | 460,614 |
| 1 day | 144 | 913856 | 914000 | 127,804 | 26ms | 4,880,898 |
| 1 week | 1008 | 912992 | 914000 | 751,769 | 69ms | 10,906,924 |
| 2 weeks | 2016 | 911984 | 914000 | 1,709,358 | 146ms | 11,722,414 |
| 1 month | 4320 | 909680 | 914000 | 4,240,572 | 341ms | 12,447,948 |
| 3 months | 12960 | 901040 | 914000 | 13,558,435 | 1s 66ms | 12,722,307 |
| 6 months | 25920 | 888080 | 914000 | 26,103,759 | 1s 613ms | 16,182,843 |
| 1 year | 52560 | 861440 | 914000 | 59,578,156 | 3s 466ms | 17,188,956 |
| 2 years | 105120 | 808880 | 914000 | 132,994,804 | 7s 690ms | 17,294,286 |

2x NVIDIA RTX 5090 (CUDA backend):

| | Blocks | Start | End | Transactions | Time | Transactions/sec |
|---|--------|-------|-----|--------------|------|------------------|
| 2 hours | 12 | 913988 | 914000 | 8,207 | 21ms | 393,537 |
| 1 day | 144 | 913856 | 914000 | 127,804 | 53ms | 2,404,792 |
| 1 week | 1008 | 912992 | 914000 | 751,769 | 92ms | 8,207,103 |
| 2 weeks | 2016 | 911984 | 914000 | 1,709,358 | 120ms | 14,213,421 |
| 1 month | 4320 | 909680 | 914000 | 4,240,572 | 158ms | 26,779,011 |
| 3 months | 12960 | 901040 | 914000 | 13,558,435 | 562ms | 24,146,078 |
| 6 months | 25920 | 888080 | 914000 | 26,103,759 | 1s 186ms | 22,012,526 |
| 1 year | 52560 | 861440 | 914000 | 59,578,156 | 2s 96ms | 28,418,826 |
| 2 years | 105120 | 808880 | 914000 | 132,994,804 | 3s 208ms | 41,455,203 |

This approach is performant enough for a multi-user public instance.
As EC computation is offloaded to the GPU, CPU overhead is low and normal Electrum server RPC calls can be handled simultaneously without any performance degradation.
Multiple clients conducting simultaneous scans slows each scan linearly, since a single scan already saturates the available GPUs.
Using multiple GPUs in the same system is also supported and the workload is scaled across them.

A discrete GPU is not required however.
Frigate can take advantage of any integrated GPU supported by OpenCL, which in practice includes almost all desktop Intel and AMD chips produced in the last decade.
This prevents saturation of the CPU, and in the case of weaker CPUs (for example older Intel NUCs) can actually be faster.
See the section below on enabling the iGPU on Linux.

### GPU Requirements

Frigate supports three GPU backends, selected automatically at runtime in order of preference:

**CUDA (NVIDIA)**
- NVIDIA Ampere or newer GPU (RTX 30xx, A100, RTX 40xx, H100, RTX 50xx)
- NVIDIA driver 570.86.15+ (Linux) or 571.14+ (Windows)
- No CUDA toolkit installation required on the host

**OpenCL (NVIDIA, Intel, AMD)**
- Any GPU with an OpenCL 1.2+ runtime
- NVIDIA: OpenCL runtime is included with the driver
- Intel: requires `intel-opencl-icd` (see [Enabling Intel iGPU on Linux](#enabling-intel-igpu-on-linux))
- AMD: requires ROCm or AMDGPU-PRO OpenCL runtime
- On Linux, the `ocl-icd-libopencl1` ICD loader is required

**Metal (Apple)**
- Apple Silicon (M1 or newer) or AMD GPU with Metal support
- macOS 12 (Monterey) or newer

### Benchmarking

The `benchmark.py` script in the project root can be used to generate the above tables against a running Frigate server:
```shell
python3 benchmark.py
python3 benchmark.py --markdown
```
The `--clients N` option runs N concurrent clients per scan period to test server behaviour under load:
```shell
python3 benchmark.py --clients 4
```

## Enabling Intel iGPU on Linux

Frigate supports GPU-accelerated scanning via OpenCL on Intel integrated GPUs.
On most Linux distributions, the Intel OpenCL runtime is not installed by default.
Note that on newer Intel CPUs, the CPU may be stronger than the GPU - but offloading the computation to the GPU is still beneficial.

### Install the Intel OpenCL runtime

On Ubuntu/Debian:
```shell
sudo apt install ocl-icd-libopencl1 intel-opencl-icd clinfo
```

`ocl-icd-libopencl1` is the OpenCL ICD loader that dispatches to vendor runtimes — it may already be installed if another GPU driver (e.g. NVIDIA) is present.
`intel-opencl-icd` is the Intel GPU compute runtime. Both can coexist with other GPU drivers without affecting them.
`clinfo` is a diagnostic tool for listing available OpenCL platforms and devices.
The OpenCL ICD (Installable Client Driver) system allows multiple GPU vendors to coexist.

### Driver compatibility

The Ubuntu-packaged driver may be outdated for newer Intel GPUs.
If Frigate crashes during startup with a newer Intel GPU, install the latest driver from Intel's PPA:
```shell
wget -qO - https://repositories.intel.com/gpu/intel-graphics.key | sudo gpg --yes --dearmor --output /usr/share/keyrings/intel-graphics.gpg
echo "deb [arch=amd64,i386 signed-by=/usr/share/keyrings/intel-graphics.gpg] https://repositories.intel.com/gpu/ubuntu $(lsb_release -cs) unified" | sudo tee /etc/apt/sources.list.d/intel-gpu.list
sudo apt update
sudo apt install intel-opencl-icd
```

### Verify

Check that the Intel GPU is visible:
```shell
clinfo | grep "Device Name"
```

You should see your Intel GPU listed (e.g. `Intel(R) UHD Graphics [0x7d67]`).

## How It Works

### Motivation

[BIP 352](https://github.com/bitcoin/bips/blob/master/bip-0352.mediawiki) proposes that light clients use compact block filters to scan for UTXOs received to a Silent Payments address.
This is workable for full nodes but problematic for light clients: a reasonable scan period requires gigabytes of tweaks, filters and blocks to be downloaded, parsed and cached on the client, far exceeding the few megabytes a traditional Electrum wallet uses to restore. 
It also offers no mempool monitoring, which users rely on to answer the "did you get my transaction?" question.

Frigate addresses both problems by moving scanning to the server, in line with the [Remote Scanner](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md#remote-scanner-ephemeral) approach in the BIP352 Index Server Specification.
The scan private key and spend public key are provided to the server, but not stored - they are held in RAM only for the duration of the client session, analogous to how public Electrum servers see ephemeral wallet addresses today.

### The Tweak Index

On startup Frigate connects to Bitcoin Core, downloads blocks from the configured start height (or Taproot activation on mainnet) and builds a tweak index - a single table containing one row per Silent-Payments-eligible transaction:

| Column       | Type         |
|--------------|--------------|
| `txid`       | BLOB         |
| `height`     | INTEGER      |
| `tweak_key`  | BLOB         |
| `outputs`    | LIST(BIGINT) |

The `txid` and `tweak_key` values are 32 and 64 byte BLOBs respectively.
The `outputs` value is a list of 8 byte integers, each representing the first 8 bytes of the x-value of the Taproot output public key.

### In-Database EC Computation

Every silent-payments scan is bounded by EC point multiplication over the tweak index, so Frigate pushes that computation as close to the rows as possible.
Instead of loading rows into the Frigate server application, scanning is performed by the database itself.
Frigate uses [DuckDB](https://duckdb.org/why_duckdb.html#fast), a fast OLAP database designed for analytical query workloads, extended with a [custom extension](https://github.com/sparrowwallet/duckdb-ufsecp-extension) wrapping [UltrafastSecp256k1](https://github.com/shrec/UltrafastSecp256k1) - a high-performance secp256k1 library with CPU and GPU backends.

Conceptually, scanning for Silent Payments requires computing the Taproot output key for `k = 0` and comparing it to the list of known output keys for each tweak row:
```sql
SELECT txid, tweak_key, height FROM tweak WHERE list_contains(outputs, hash_prefix_to_int(secp256k1_ec_pubkey_combine([SPEND_PUBLIC_KEY, secp256k1_ec_pubkey_create(secp256k1_tagged_sha256('BIP0352/SharedSecret', secp256k1_ec_pubkey_tweak_mul(tweak_key, SCAN_PRIVATE_KEY) || int_to_big_endian(0)))]), 1));
```
The client can then download each matching transaction and determine if it does indeed contain outputs it is interested in, including for higher values of `k`.

Frigate performs all of these steps at once using a single scanning function that also includes a further step to scan for change:
```sql
SELECT txid, tweak_key, height FROM ufsecp_scan((SELECT txid, height, tweak_key, outputs FROM tweak), SCAN_PRIVATE_KEY, SPEND_PUBLIC_KEY, [CHANGE_TWEAK_KEY]);
```
The change tweak is added to the computed P<sub>0</sub> and checked again against the outputs for a match.
All inputs are provided in little endian format, with public keys in uncompressed little endian x,y format to avoid decompression overhead on each tweak.
UltrafastSecp256k1 uses batch affine addition and KPlan-optimized scalar multiplication to maximize throughput on CPU, and can leverage CUDA, OpenCL or Metal to offload EC computation to the GPU for significantly higher performance.

## Electrum Protocol

The Electrum protocol is by far the most widely used light client protocol for Bitcoin wallets, and support is now almost a requirement for widespread adoption of any wallet technology proposal.
It is characterised by resource efficiency for the client in terms of bandwidth, CPU and storage, allowing a good user experience on almost any platform.
It has however been designed around BIP32 wallets.
Silent Payments presents an alternative model, where instead of an incrementing derivation path index (and associated gap limit) transactions must be found through scanning the blockchain.
As such, new methods are necessary.
Frigate implements the following Electrum JSON-RPC methods, proposed for inclusion in the Electrum protocol:

### server.features

Servers supporting silent payments advertise the capability by including a `silent_payments` field in the `server.features` response:

```json
{
  ...,
  "silent_payments": [0]
}
```

Each integer is a fully-specified silent payments protocol version supported by the server.
BIP352 defines version 0.

### blockchain.silentpayments.subscribe

**Signature**
```
blockchain.silentpayments.subscribe(scan_private_key, spend_public_key, start, labels)
```

- _scan_private_key_: A 64 character string containing the hex of the scan private key.
- _spend_public_key_: A 66 character string containing the hex of the spend public key.
- _start_: (Optional) An integer block height to start scanning from, or a string of the form `"FROM-TO"` to specify a closed range of heights. Integer values above 500,000,000 are treated as seconds from the start of the epoch.
- _labels_: (Optional) An array of positive integers specifying additional silent payment labels to scan for. Change (`m = 0`) is always included regardless. To aid in wallet recovery, this parameter should only be used for specialized applications.

**Result**

A `subscription` JSON object literal containing details of the current subscription:
- _address_: The silent payment address that has been subscribed to.
- _labels_: An array of the labels that are subscribed to (must include `0`).
- _start_height_: The block height from which the subscription scan was started.

If a subscription for the same scan address already exists on the connection, servers **must not** narrow its coverage.
If the new request's resolved start height is greater than the existing subscription's `start_height`, the server retains the existing wider start and returns it as `start_height` in the response.
Clients should treat the returned `start_height` as authoritative.
On every subscribe (including re-subscribe to the same address), the server **must** re-run the historical scan from the returned `start_height` and emit notifications as usual, so that clients which clear their local cache on re-subscribe are repopulated.

**Result Example**

```json
{
  "address": "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv",
  "labels": [0],
  "start_height": 882000
}
```

### Notifications

Once subscribed, the client will receive notifications as results are returned from the scan with the following signature.
Servers **must** send the response to `blockchain.silentpayments.subscribe` before any notification for that subscription.
The `subscription` object in every notification **must** be byte-identical to the one returned in the original `blockchain.silentpayments.subscribe` response with the same `address`, `labels` and `start_height`.
It identifies which subscription the notification belongs to, not the range that the server happened to scan to produce it.
All historical (`progress` < `1.0`) results **must** be sent before current (up to date) results.
Servers **must** also deliver silent payments notifications before any scripthash notification arising from the same block or mempool event, since the silent payments subscription is the discovery channel through which clients learn which scripthashes to subscribe to.
A `progress` of `1.0` indicates the scan is up to date as of this notification.
The first such notification marks the end of the historical scan; subsequent `1.0` notifications represent live updates as new blocks and mempool transactions are scanned.
Clients should flush any buffered history on every `1.0` notification.

```
blockchain.silentpayments.subscribe(subscription, progress, history)
```

**Result**

A dictionary with the following key/value pairs:

1. A `subscription` JSON object literal containing details of the current subscription:
- _address_: The silent payment address that has been subscribed to.
- _labels_: An array of the labels that are subscribed to (must include `0`).
- _start_height_: The block height from which the subscription scan was started.

2. A `progress` key/value pair indicating the progress of a historical scan:
- _progress_: A floating point value between `0.0` and `1.0`. Will be `1.0` for all current (up to date) results.

3. A `history` array of transactions. Confirmed transactions are listed in order by height. Each transaction is a dictionary with the following keys:
- _height_: The integer height of the block the transaction was confirmed in. For mempool transactions, `0` should be used.
- _tx_hash_: The transaction hash in hexadecimal.
- _tweak_key_: The tweak key (`input_hash*A`) for the transaction in compressed format.

**Result Example**

```json
{
  "subscription": {
    "address": "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv",
    "labels": [0],
    "start_height": 882000
  },
  "progress": 1.0,
  "history": [
    {
      "height": 890004,
      "tx_hash": "acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412",
      "tweak_key": "0314bec14463d6c0181083d607fecfba67bb83f95915f6f247975ec566d5642ee8"
    },
    {
      "height": 905008,
      "tx_hash": "f3e1bf48975b8d6060a9de8884296abb80be618dc00ae3cb2f6cee3085e09403",
      "tweak_key": "024ac253c216532e961988e2a8ce266a447c894c781e52ef6cee902361db960004"
    },
    {
      "height": 0,
      "tx_hash": "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16",
      "tweak_key": "03aeea547819c08413974e2ab2b12212e007166bb2058f88b009e082b9b4914a58"
    }
  ]
}
```

It is recommended that servers implementing this protocol send history results incrementally as the historical scan progresses.
In addition, a maximum page size of 100 is suggested when sending historical transactions.
This will avoid transmission issues with large wallets that have many transactions, while providing the client with regular progress updates.
Servers should also emit a notification with empty `history` at regular intervals (e.g. every 5 seconds) during a historical scan, to keep the client updated on scanning progress.
In the case of block reorgs, the server should rescan all existing subscriptions from the reorg-ed block height and send any history (if found) to the client.
All found mempool transactions should be sent on the initial subscription, but thereafter previously sent mempool transactions should not be resent.
The server **must not** re-notify a previously sent mempool transaction once it confirms as clients track confirmation state via `blockchain.scripthash.subscribe`.
The silent payments subscription is purely a discovery channel.

Clients reconnecting with prior history should pass `start = lastSeenHeight - reorgLimit` to limit the rescan to a recent window.
A reorg limit of 100 blocks is sufficient.

For every transaction announced via this subscription, clients **must** retrieve it with `blockchain.transaction.get` and subscribe to all owned outputs with `blockchain.scripthash.subscribe`.
The scripthash subscription is the canonical source of confirmation height, reorg recovery, and spend tracking; the silent payments subscription does not duplicate any of these.
In other words, the silent payments address subscription is a replacement for the monotonically increasing derivation path index in BIP32 wallets.
The subscription seeks only to add to the client's knowledge of incoming silent payments transactions.
The client is responsible for checking the transactions do actually send to addresses it has keys for, and using normal Electrum wallet synchronization techniques to monitor for changes to these addresses.
The tweak key is provided to allow the client to avoid looking up the scriptPubKeys of spent outputs.

### blockchain.silentpayments.unsubscribe

**Signature**
```
blockchain.silentpayments.unsubscribe(scan_private_key, spend_public_key)
```

- _scan_private_key_: A 64 character string containing the hex of the scan private key.
- _spend_public_key_: A 66 character string containing the hex of the spend public key.

**Result**

The silent payment address that has been unsubscribed. This should cancel any scans that may be currently running for this address.

**Result Example**

```
sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv
```

## Scan Audit Mode

Frigate includes a correctness testing mode to verify the database extension will find all outputs for a given wallet.
In normal operation, the tweak index stores the hash prefixes derived from the taproot output public keys of each eligible transaction.
In audit mode, the index instead stores the hash prefix of the expected change output key (P<sub>0</sub> = B<sub>spend</sub> + t<sub>0</sub>·G) computed from the provided wallet keys.
This treats every silent payments eligible transaction as paying to the provided wallet.
The audit can then be run with the following query:
```sql
SELECT (SELECT COUNT(*) FROM tweak) AS total_rows, COUNT(*) AS matched_rows FROM ufsecp_scan((SELECT txid, height, tweak_key, outputs FROM tweak), from_hex('<scan_private_key_little_endian>'), from_hex('<spend_public_key_little_endian_x_y>'), [from_hex('<change_label_key_little_endian_x_y>')]); 
```

This mode is activated by setting two environment variables before starting Frigate:

```shell
export FRIGATE_AUDIT_SCAN_KEY=<scan_private_key_hex>
export FRIGATE_AUDIT_SPEND_KEY=<spend_public_key_hex>
```

When both variables are set, a warning is logged on startup confirming audit mode is active.
The index must be rebuilt from scratch when switching between normal and audit modes.

## Building

To clone this project, use

`git clone --recursive git@github.com:sparrowwallet/frigate.git`

or for those without SSH credentials:

`git clone --recursive https://github.com/sparrowwallet/frigate.git`

In order to build, Frigate requires Java 25 or higher to be installed.
The release binaries are built with [Eclipse Temurin 25.0.2+10](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.2%2B10).
If you are using [SDKMAN](https://sdkman.io/), you can use `sdk env install` to ensure you have the correct version.

Other packages may also be necessary to build depending on the platform. On Debian/Ubuntu systems:

`sudo apt install -y rpm fakeroot binutils`

The Frigate binaries can be built from source using

`./gradlew jpackage`

Note that to build the Windows installer, you will need to install [WiX](https://github.com/wixtoolset/wix3/releases).

When updating to the latest HEAD

`git pull --recurse-submodules`

## Reporting Issues

Please use the [Issues](https://github.com/sparrowwallet/frigate/issues) tab above to report an issue. If possible, look in the frigate.log file in the configuration directory for information helpful in debugging.

## License

Frigate is licensed under the Apache 2 software licence.

## GPG Key

The Frigate release binaries here are signed using [craigraw's GPG key](https://keybase.io/craigraw):  
Fingerprint: D4D0D3202FC06849A257B38DE94618334C674B40  
64-bit: E946 1833 4C67 4B40
