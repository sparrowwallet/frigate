![Frigate logo](https://github.com/sparrowwallet/frigate/raw/refs/heads/master/frigatelogo.png)

# Frigate Electrum Server
 
Frigate is an experimental Electrum Server testing Silent Payments scanning with ephemeral client keys.

It has three goals:
1. To provide a proof of concept implementation of the [Remote Scanner](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md#remote-scanner-ephemeral) approach discussed in the BIP352 Silent Payments Index Server [Specification](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md) (WIP).
2. To propose Electrum RPC protocol methods to request and return Silent Payments information from a server.
3. To demonstrate an efficient "in database" technique of scanning for Silent Payments transactions.

#### This is alpha software, and should not be used in production.

## Motivation

[BIP 352](https://github.com/bitcoin/bips/blob/master/bip-0352.mediawiki) has proposed that light clients use compact block filters to scan for UTXOs received to a Silent Payments address.
However, this introduces two significant problems:

The first is one of data gravity.
For any reasonable scan period, the client must download gigabytes of data in tweaks, block filters and finally some of the blocks themselves.
All this data needs to be downloaded, parsed and potentially saved to avoid downloading it again, requiring significant resources on the client. 
A client would likely need several gigabytes of data to restore a wallet with historical transactions, which is resource intensive in terms of bandwidth, CPU and storage. 
Compare this to current Electrum clients which may use just a few megabytes to restore a wallet, and it's easy to see how this approach is unlikely to see widespread adoption - it's just too onerous, particularly for mobile clients.

The second problem is the lack of mempool monitoring, which is not supported with compact block filters. 
Users rely on mempool monitoring to answer the "did you get my transaction?" question.
The lack of ability to do this can cause user confusion and distrust in the wallet, which education can only go some way in reducing.

This project attempts to address these problems using an Electrum protocol styled approach.
Instead of asking the client to download the required data and perform the scanning, the server performs the scanning locally with an optimized index.
This is the [Remote Scanner](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md#remote-scanner-ephemeral) approach discussed in the BIP352 Silent Payments Index Server [Specification](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md) (WIP).
It should be noted that both the scan private key and the spend public key must be provided to the server in this approach.
While this does have a privacy implication, the keys are not stored and only held by the server ephemerally (in RAM) for duration of the client session.
This is similar to the widely used public Electrum server approach, where the wallet addresses are shared ephemerally with a public server. 

## Approach

The key problem that BIP 352 introduces with respect to scanning is that much of the computation cannot be done generally ahead of time.
Instead, for every silent payment address, each transaction in the blockchain must be considered separately to determine if it sends funds to that address.
The computation involves several cryptographic operations, including two resource intensive EC point multiplication operations on _every_ eligible transaction.
In order to ensure that client keys are ephemeral and not stored, this computation must be done in a reasonable period of time on millions of transactions.

This is the key difference between Silent Payments wallets and traditional BIP32 wallets, which can rely on a simple monotonically incrementing derivation path index.
While Silent Payments provides important advantages in privacy and user experience, this computational burden is the downside that cannot be avoided.
Any solution addressing the retrieval of Silent Payments transactions will eventually be bounded by the performance of EC point multiplication.
For best performance and user experience this should be done as efficiently as possible, and therefore as close the source data as possible.

In order to achieve this, Frigate addresses the problem of data gravity directly.
Like most light client silent payment services, it builds an index of the data that can be pre-computed, generally known as a tweak index.
This index contains a large number of elements (one for every silent payments eligible transaction in the blockchain) containing a tweak calculated from the public keys used in the transaction inputs.
Frigate stores this data in a single table with the following schema:

| Column       | Type         |
|--------------|--------------|
| `txid`       | BLOB         |
| `height`     | INTEGER      |
| `tweak_key`  | BLOB         |
| `outputs`    | LIST(BIGINT) |

The `txid` and `tweak_key` values are 32 and 33 byte BLOBS respectively. 
The `outputs` value is a list of 8 byte integers, each representing the first 8 bytes of the x-value of the Taproot output public key.

On startup, Frigate connects to the configured Bitcoin Core RPC, downloads blocks from the configured block height (or from Taproot activation on mainnet) and adds entries to the table.
Once it has reached the blockchain tip, it starts a simple (and incomplete) Electrum Server to interface with the client.

The scanning is the interesting part.
Instead of loading data from the table into the Frigate server application, the database itself performs all the required cryptographic operations. 
To do this, Frigate uses a fast OLAP database called [DuckDB](https://duckdb.org/why_duckdb.html#fast) designed for analytical query workloads.
It then extends the database with [a custom extension](https://github.com/sparrowwallet/duckdb-secp256k1-extension) that adds functions from [libsecp256k1](https://github.com/bitcoin-core/secp256k1).
This allows Frigate to perform functions such as
```sql
SELECT secp256k1_ec_pubkey_tweak_mul(tweak_key, scalar);
```
which allows the EC point computation to happen as close to the tweak data as possible.

With these extensions, Frigate performs a query as follows:
```sql
SELECT txid, height FROM tweak WHERE list_contains(outputs, hash_prefix_to_int(secp256k1_ec_pubkey_combine([SPEND_PUBLIC_KEY, secp256k1_ec_pubkey_create(secp256k1_tagged_sha256('BIP0352/SharedSecret', secp256k1_ec_pubkey_tweak_mul(tweak_key, SCAN_PRIVATE_KEY) || int_to_big_endian(0)))]), 1));
```
This computes the Taproot output key for `k = 0` and compares it to the list of known keys for each tweak row, returning the `txid` and `height` if there is a match.
The client can then download the transaction and determine if it does indeed contain outputs it is interested in, including for higher values of `k`.

## Electrum protocol

The Electrum protocol is by far the most widely used light client protocol for Bitcoin wallets, and support is now almost a requirement for widespread adoption of any wallet technology proposal.
It is characterised by resource efficiency for the client in terms of bandwidth, CPU and storage, allowing a good user experience on almost any platform. 
It has however been designed around BIP32 wallets. 
Silent Payments presents an alternative model, where instead of an incrementing derivation path index (and associated gap limit) transactions must be found through scanning the blockchain.
As such, new methods are necessary.
Frigate proposes the following Electrum JSON-RPC methods:

### blockchain.silentpayments.subscribe

**Signature**
```
blockchain.silentpayments.subscribe(scan_private_key, spend_public_key, start)
```

- _scan_private_key_: A 64 character string containing the hex of the scan private key.
- _spend_public_key_: A 66 character string containing the hex of the spend public key.
- _start_: (Optional) Block height or timestamp to start scanning from. Values above 500,000,000 are treated as seconds from the start of the epoch.

**Result**

The silent payment address that has been subscribed.

**Result Example**

```
sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv
```

### Notifications

Once subscribed, the client will receive notifications as results are returned from the scan with the following signature. 
All historical (`progress` < `1.0`) results **must** be sent before current (up to date) results.
Once the client has received a notification with `progress` == `1.0`, it should consider the scan complete.

```
blockchain.silentpayments.subscribe(subscription, progress, history)
```

**Result**

A dictionary with the following key/value pairs:

1. A `subscription` JSON object literal containing details of the current subscription:
- _address_: The silent payment address that has been subscribed to.
- _start_height_: The block height from which the subscription scan was started.

2. A `progress` key/value pair indicating the progress of a historical scan:
- _progress_: A floating point value between `0.0` and `1.0`. Will be `1.0` for all current (up to date) results.

3. A `history` array of transactions. Confirmed transactions are listed in blockchain order. Each transaction is a dictionary with the following keys:
- _height_: The integer height of the block the transaction was confirmed in. For mempool transactions, `0` should be used.
- _tx_hash_: The transaction hash in hexadecimal.

**Result Example**

```json
{
  "subscription": {
    "address": "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv",
    "start_height": 882000
  },
  "progress": 1.0,
  "history": [
    {
      "height": 890004,
      "tx_hash": "acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412"
    },
    {
      "height": 905008,
      "tx_hash": "f3e1bf48975b8d6060a9de8884296abb80be618dc00ae3cb2f6cee3085e09403"
    },
    {
      "height": 0,
      "tx_hash": "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16"
    }
  ]
}
```

It is recommended that servers implementing this protocol send history results incrementally as the historical scan progresses.
In addition, a maximum page size of 100 is suggested when sending historical transactions.
This will avoid transmission issues with large wallets that have many transactions, while providing the client with regular progress updates.
In the case of block reorgs, the server should rescan all existing subscriptions from the reorg-ed block height and send any history (if found) to the client.
All found mempool transactions should be sent on the initial subscription, but thereafter previously sent mempool transactions should not be resent.

Clients should retrieve the transactions listed in the history with `blockchain.transaction.get` and subscribe to all owned outputs with `blockchain.scripthash.subscribe`. 
Electrum wallet functionality then proceeds as normal.
In other words, the silent payments address subscription is a replacement for the monotonically increasing derivation path index in BIP32 wallets.
The subscription seeks only to add to the client's knowledge of incoming silent payments transactions.
The client is responsible for checking the transactions do actually send to addresses it has keys for, and using normal Electrum wallet synchronization techniques to monitor for changes to these addresses. 

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

### blockchain.silentpayments.tweaks

**Signature**
```
blockchain.silentpayments.tweaks(block_height)
```

- _block_height_: An integer representing the block height to query tweaks for.

**Result**

An array of tweak entries for all Silent Payments eligible transactions found in the specified block. Each entry contains the transaction ID and the corresponding tweak key.

**Result Example**

```json
[
  {
    "txid": "acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412",
    "tweak": "02a1b2c3d4e5f67890abcdef1234567890abcdef1234567890abcdef12345678"
  },
  {
    "txid": "f3e1bf48975b8d6060a9de8884296abb80be618dc00ae3cb2f6cee3085e09403", 
    "tweak": "03b2c3d4e5f67890abcdef1234567890abcdef1234567890abcdef1234567890"
  }
]
```

This method provides direct access to the raw tweak data stored in the database for a specific block height, which can be useful for debugging, analysis, or building custom Silent Payments scanning tools. Unlike the subscription-based methods, this endpoint does not require cryptographic keys and simply returns the stored tweak information.

## Performance

The scanning query is essentially CPU bound, mostly around EC point multiplication.
[DuckDB parallelizes](https://duckdb.org/docs/stable/guides/performance/how_to_tune_workloads#parallelism-multi-core-processing) the workload based on row groups, with each row group containing 122,880 rows.
It will by default configure itself to use all the available cores on the server it is running.
The behaviour can be configured in the Frigate configuration file (see `dbThreads`).

The following set of benchmarks was generated on a M1 Macbook Pro with 10 available CPUs, scanning mainnet to a block height of 911434 with a database size of ~13Gb.
**Note that no cut-through or dust filter has been used.**

|                       |   Blocks  |   Start   | Transactions |   Time       | Transactions/sec |
|-----------------------|-----------|-----------|--------------|--------------|------------------|
|   2 hours             |   12      |   911422  | 8961         |   474ms      | 18905            |
|   1 day               |   144     |   911290  | 149059       |   5s 6ms     | 29776            |
|   1 week              |   1008    |   910426  | 1143906      |   7s 992ms   | 143131           |
|   2 weeks             |   2016    |   909418  | 2349028      |   17s 408ms  | 134940           |
|   4 weeks             |   4032    |   907402  | 5002030      |   36s 796ms  | 135940           |
|   8 weeks             |   8064    |   903370  | 9441899      |   1m 6s      | 142101           |
|   16 weeks            |   16128   |   895306  | 15910877     |   1m 51s     | 143269           |
|   32 weeks            |   32256   |   879178  | 32666940     |   3m 47s     | 143638           |
|   64 weeks            |   64512   |   846922  | 77427166     |   8m 55s     | 144606           |
|   Taproot Activation  |   201802  |   709632  | 153651412    |   17m 25s    | 147043           |

Higher performance on the longer periods is possible by increasing the number of CPUs.
The following set of benchmarks was generated on an Intel server with 32 cores using the same tweak database:

|                    |   Blocks  |   Start   | Transactions | Time      | Transactions/sec |
|--------------------|-----------|-----------|--------------|-----------|------------------|
| 2 hours            |   12      |   911422  | 8961         | 1s 345ms  | 6662             |
| 1 day              |   144     |   911290  | 149059       | 7s 703ms  | 19351            |
| 1 week             |   1008    |   910426  | 1143906      | 9s 625ms  | 118847           |
| 2 weeks            |   2016    |   909418  | 2349028      | 14s 714ms | 159646           |
| 4 weeks            |   4032    |   907402  | 5002030      | 27s 666ms | 180801           |
| 8 weeks            |   8064    |   903370  | 9441899      | 44s 979ms | 209918           |
| 16 weeks           |   16128   |   895306  | 15910877     | 1m 20s    | 199695           |
| 32 weeks           |   32256   |   879178  | 32666940     | 2m 30s    | 217561           |
| 64 weeks           |   64512   |   846922  | 77427166     | 5m 45s    | 224315           |
| Taproot Activation |   201802  |   709632  | 153651412    | 11m 34s   | 221502           |

Multiple clients conducting simultaneous scans slows each scan linearly. 
Further performance improvements (or handling additional clients) may be performed by scaling out across [multiple read-only replicas of the database](https://motherduck.com/docs/key-tasks/authenticating-and-connecting-to-motherduck/read-scaling/).
It is also possible to consider hardware acceleration techniques such as [HSMs](https://docs.aws.amazon.com/cloudhsm/latest/userguide/performance.html), [cryptographic coprocessors](https://developer.arm.com/Processors/CryptoCell-310) or GPU acceleration.

## Configuration

For indexing Frigate will need access to the Bitcoin Core RPC, which will need to have `txindex=1` configured.

By default Frigate stores all configuration in `~/.frigate/config` on macOS and Linux, and `%APPDATA%/Frigate` on Windows.
An example configuration looks as follows
```json
{
  "coreServer": "http://127.0.0.1:8332",
  "coreAuthType": "COOKIE",
  "coreDataDir": "/home/bitcoin/.bitcoin",
  "coreAuth": "bitcoin:password",
  "startIndexing": true,
  "indexStartHeight": 0,
  "scriptPubKeyCacheSize": 10000000
}
```

Default values for these entries will be set on first startup.
The value of `coreAuthType` can either be `COOKIE` or `USERPASS`. 
Configure `coreDataDir` or `coreAuth` respectively to grant RPC access.
The value of `startIndexing` can be set to `false` if an index has already been built and you just want to execute queries against it without connecting to Bitcoin Core.

Indexing speed is greatly affected by looking up the scriptPubKeys of spent outputs.
To improve performance, scriptPubKeys are cached to avoid looking them up again with `getrawtransaction`.
The `scriptPubKeyCacheSize` limits the number of scriptPubKeys cached during indexing. 
The default value leads to a total application memory size of around 4Gb. 
This value can be increased or decreased depending on available RAM. 

The DuckDB database is stored in a `db` subfolder in the same directory, in a file called `frigate.duckdb`.
DuckDB databases can be transferred between different operating systems, and should survive unclean shutdowns.

To reduce CPU load while scanning, add an entry to reduce the number of cores made available to DuckDB, for example:
```json
{
  "dbThreads": 2
}
```

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

#### Querying Block Tweaks

The CLI can also be used to query tweaks for a specific block height using the `--height` parameter. This mode bypasses Silent Payments scanning and directly returns the raw tweak data from the database:

```shell
./bin/frigate-cli --height 890000
```

This will output a JSON array of all tweaks found in block 890000:
```json
[
  {
    "txid": "acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412",
    "tweak": "02a1b2c3d4e5f67890abcdef1234567890abcdef1234567890abcdef12345678"
  }
]
```

The tweaks query mode can be combined with network parameter:
```shell
./bin/frigate-cli --height 890000 --network signet
```

## Building

To clone this project, use

`git clone --recursive git@github.com:sparrowwallet/frigate.git`

or for those without SSH credentials:

`git clone --recursive https://github.com/sparrowwallet/frigate.git`

In order to build, Frigate requires Java 22 or higher to be installed.
The release binaries are built with [Eclipse Temurin 22.0.2+9](https://github.com/adoptium/temurin22-binaries/releases/tag/jdk-22.0.2%2B9).

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

