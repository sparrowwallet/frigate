# Frigate Electrum Server
 
Frigate is an experimental Electrum Server testing Silent Payments scanning with ephemeral client keys.

#### This is alpha software, and should not be used in production.

## Motivation

[BIP 352](https://github.com/bitcoin/bips/blob/master/bip-0352.mediawiki) has proposed that light clients use compact block filters to scan for UTXOs received to a Silent Payments address.
However, this introduces two significant problems:

The first is one of data gravity.
For any reasonable scan period, the client must download gigabytes of data in tweaks, block filters and finally some of the blocks themselves.
All this data needs to be downloaded, parsed and potentially saved to avoid downloading it again, requiring significant resources on the client. 
One could quickly see wallets use many gigabytes per month in this manner, which is resource intensive in terms of bandwidth, CPU and storage. 
Compare this to current Electrum clients which may use a few megabytes per month, and it's easy to see how this approach is unlikely to see widespread adoption - it's just too onerous. 

The second problem is the lack of mempool monitoring, which is not supported with compact block filters. 
Users rely on to answer the "did you get my transaction?" question.
The lack of ability to do this can cause user confusion and distrust in the wallet, and education can only go some way in reducing it.

This project attempts to address these problems using an Electrum protocol styled approach.
Instead of asking the client to download the required data and perform the scanning, the server performs the scanning locally with an optimized index.
This is the [Remote Scanner](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md#remote-scanner-ephemeral) approach discussed in the BIP352 Silent Payments Index Server [Specification](https://github.com/silent-payments/BIP0352-index-server-specification/blob/main/README.md) (WIP).
It should be noted that both the scan private key and the spend public key must be provided to the server in this approach.
While this does have a privacy implication, the keys are not stored and only held by the server ephemerally for the client session.
This is similar to the widely used public Electrum server approach, where the wallet addresses are shared ephemerally with a public server. 

## Approach

The key problem that BIP 352 introduces with respect to scanning is that much of the computation cannot be done generally ahead of time.
Instead, for every silent payment address, each transaction in the blockchain must be considered separately to determine if it sends funds to that address.
In order to ensure that client keys are ephemeral and not stored, this computation must be done in a reasonable period of time on millions of transactions.

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

The `txid` and `tweak_key` values are 32 byte BLOBS. 
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
which allows computation to happen as close to the tweak data as possible.

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
- _start_: (Optional) Block height or timestamp to start scanning from. Values above 500,000 are treated as seconds from the start of the epoch.

**Result**

The silent payment address that has been subscribed.

**Result Example**

```json
sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv
```

### Notifications

Once subscribed, the client will receive notifications as results are returned from the scan with the following signature:

```
blockchain.silentpayments.subscribe(subscription, progress, history)
```

**Result**

A dictionary with the following key/value pairs:

1. A `subscription` JSON object literal containing details of the current subscription:
- _address_: The silent payment address that has been subscribed to.
- _startHeight_: The block height from which the subscription scan was started.

2. A `progress` key/value pair indicating the progress of a historical scan:
- _progress_: A floating point value between `0.0` and `1.0`. Will be `1.0` for all current (up to date) results.

3. A `history` array of confirmed transactions in blockchain order. Each confirmed transaction is a dictionary with the following keys:
- _height_: The integer height of the block the transaction was confirmed in. For mempool transactions, `0` if all inputs are confirmed, and `-1` otherwise.
- _tx_hash_: The transaction hash in hexadecimal.

**Result Example**

```json
{
  "subscription": {
    "address": "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv",
    "startHeight": 882000
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
    }
  ]
}
```

It is recommended that servers implementing this protocol send history results incrementally as the historical scan progresses.
In addition, a maximum page size of 100 history items is suggested.
This will avoid transmission issues with large wallets that have many transactions, while providing the client with regular progress updates.

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

```json
sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv
```

## Performance

The scanning query is essentially CPU bound.
[DuckDB parallelizes](https://duckdb.org/docs/stable/guides/performance/how_to_tune_workloads#parallelism-multi-core-processing) the workload based on row groups, with each row group containing 122,880 rows.
It will by default configure itself to use all the available cores on the server it is running.
The behaviour can be configured in the Frigate configuration file (see `dbThreads`).

An example benchmark is scanning the entire tweak database for the signet chain as of 21 August 2025.
This query takes just over 2 minutes on a Macbook Pro M1. 

However, it is more typical for queries to be limited to a range of blocks, usually from the date the wallet was created until the current block tip.

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
  //Add this to reduce CPU load: "dbThreads": 2
}
```
Default values for these entries will be set on first startup.
The value of `coreAuthType` can either be `COOKIE` or `USERPASS`. 
Configure `coreDataDir` or `coreAuth` respectively to grant RPC access.
The value of `startIndexing` can be set to false if the index has already been built and you want to just execute queries against it.

Indexing speed is greatly affected by looking up the scriptPubKeys of spent outputs.
To improve performance, scriptPubKeys are cached to avoid looking them up again with `getrawtransaction`.
The `scriptPubKeyCacheSize` limits the number of scriptPubKeys cached during indexing. 
The default value leads to a total application memory size of around 4Gb. 
This value can be increased or decreased depending on available RAM. 

The DuckDB database is stored in a `db` subfolder in the same directory, in a file called `duckdb`.
DuckDB databases can be transferred between different operating systems, and should survive unclean shutdowns.

## Usage

The Frigate server may be started as follows:
```shell
./bin/frigated
```

To start with a different network, use the `-n` parameter:
```shell
./bin/frigated -n signet
```

The full range of options can be queried with:
```shell
./bin/frigated -h
```

### Frigate CLI

Frigate also ships a CLI tool called `frigate-cli` to allow easy access to the Electrum RPC.
```shell
./bin/frigate-cli
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

