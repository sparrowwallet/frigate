# Frigate Electrum Server
 
Frigate is an experimental Electrum Server testing Silent Payments scanning with ephemeral client keys.

#### This is alpha software, and should not be used in production.

## Motivation

[BIP0352](https://github.com/bitcoin/bips/blob/master/bip-0352.mediawiki) has proposed that light clients use compact block filters to scan for UTXOs received to a Silent Payments address.
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

The key problem that BIP0352 introduces with respect to scanning is that much of the computation cannot be done generally ahead of time.
Instead, for every silent payment address, each transaction must be considered separately to determine if it sends funds to that address.
In order to ensure that client keys are ephemeral and not stored, this computation must be done in a reasonable period of time.

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

Frigate contains the following Electrum JSON-RPC methods:

#### blockchain.silentpayments.get_history

**Signature**
```
blockchain.silentpayments.get_history(scan_private_key, spend_public_key, start_height, end_height)
```

- _scan_private_key_: A 64 character string containing the hex of the scan private key
- _spend_public_key_: A 66 character string containing the hex of the spend public key
- _start_height_: (Optional) Block height to start scanning from
- _end_height_: (Optional) Block height to stop scanning at

**Result**

A list of confirmed transactions in blockchain order. Each confirmed transaction is a dictionary with the following keys:
- _height_: The integer height of the block the transaction was confirmed in.
- _tx_hash_: The transaction hash in hexadecimal.

**Result Examples**

```json
[
  {
    "height": 890004,
    "tx_hash": "acc3758bd2a26f869fcc67d48ff30b96464d476bca82c1cd6656e7d506816412"
  },
  {
    "height": 905008,
    "tx_hash": "f3e1bf48975b8d6060a9de8884296abb80be618dc00ae3cb2f6cee3085e09403"
  }
]
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

By default Frigate stores all configuration in `~/.frigate/config` on macOS and Linux, and `%APPDATA%/Frigate` on Windows.
An example configuration looks as follows
```json
{
  "coreServer": "http://127.0.0.1:8332",
  "coreAuthType": "USERPASS",
  "coreDataDir": "/home/bitcoin/.bitcoin",
  "coreAuth": "bitcoin:password",
  "startIndexing": true,
  "indexStartHeight": 0,
  "scriptPubKeyCacheSize": 1000000
  //Add this to reduce CPU load: "dbThreads": 2
}
```
The DuckDB database is stored in a `db` subfolder in the same directory.

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

Frigate also ships a CLI tool called `frigate-cli` to allow easy access to the Electrum scanning RPC.
```shell
./bin/frigate-cli
```

It uses similar arguments, for example:
```shell
./bin/frigate-cli -n signet
```

The scan private key and spend public key, along with start and end block heights, can be specified as arguments or are prompted for:
```shell
./bin/frigate-cli -s SCAN_PRIVATE_KEY -S SPEND_PUBLIC_KEY -b 890000 -e 900000
```

```shell
./bin/frigate-cli
Enter scan private key: SCAN_PRIVATE_KEY
Enter spend public key: SPEND_PUBLIC_KEY
Enter start height (optional, press Enter to skip): 890000
Enter end height (optional, press Enter to skip): 900000
```