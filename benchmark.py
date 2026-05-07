#!/usr/bin/env python3

import argparse
import concurrent.futures
import socket
import ssl
import json
import sys
import time

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 57001
DEFAULT_END_HEIGHT = 914000

SCAN_KEY = "3c8b12d524c72d91dad33573c18f17dddb8f45e8d60c711c49a5a7992e321364"
SPEND_KEY = "0377dd40dfd0da11369dc6bddf6b3bf4f0474383a8beb6e523dddabc0f966734a6"

# (descriptive label, short label, blocks)
PERIODS = [
    ("2 hours",  "2h",  12),
    ("1 day",    "1d",  144),
    ("1 week",   "1w",  1008),
    ("2 weeks",  "2w",  2016),
    ("1 month",  "1m",  4320),
    ("3 months", "3m",  12960),
    ("6 months", "6m",  25920),
    ("1 year",   "1y",  52560),
    ("2 years",  "2y",  105120),
]

# Transaction counts at endHeight=914000 (for display only)
TRANSACTION_COUNTS = {
    "2h":  8207,
    "1d":  127804,
    "1w":  751769,
    "2w":  1709358,
    "1m":  4240572,
    "3m":  13558435,
    "6m":  26103759,
    "1y":  59578156,
    "2y":  132994804,
}


def scan(host, port, start_range, tls=False):
    raw = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    raw.settimeout(600)
    if tls:
        ctx = ssl.create_default_context()
        sock = ctx.wrap_socket(raw, server_hostname=host)
    else:
        sock = raw
    sock.connect((host, port))

    sock.sendall(json.dumps({
        "jsonrpc": "2.0", "method": "server.version",
        "params": ["benchmark", "1.4"], "id": 1
    }).encode() + b"\n")

    req = json.dumps({
        "jsonrpc": "2.0", "method": "blockchain.silentpayments.subscribe",
        "params": {
            "scan_private_key": SCAN_KEY,
            "spend_public_key": SPEND_KEY,
            "start": start_range,
        }, "id": 2
    }).encode() + b"\n"

    t0 = time.monotonic()
    sock.sendall(req)

    buf = b""
    while True:
        data = sock.recv(8192)
        if not data:
            break
        buf += data
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            msg = json.loads(line)
            if "params" in msg and msg["params"].get("progress", 0) >= 1.0:
                elapsed = time.monotonic() - t0
                sock.close()
                return elapsed, len(msg["params"].get("history", []))
            if msg.get("id") == 2 and "error" in msg:
                sock.close()
                raise RuntimeError(msg["error"].get("message", str(msg["error"])))

    elapsed = time.monotonic() - t0
    sock.close()
    return elapsed, 0


def format_time(seconds):
    ms = round(seconds * 1000)
    if ms < 1000:
        return f"{ms}ms"
    s = ms // 1000
    remainder_ms = ms % 1000
    if s < 60:
        return f"{s}s {remainder_ms}ms"
    m = s // 60
    remainder_s = s % 60
    return f"{m}m {remainder_s}s"


def format_number(n):
    return f"{n:,}"


def run_benchmarks(host, port, end_height, markdown, clients, max_periods=0, tls=False):
    period_list = PERIODS[:max_periods] if max_periods > 0 else PERIODS
    periods = []
    for desc, short, blocks in period_list:
        start = end_height - blocks
        txns = TRANSACTION_COUNTS.get(short) if end_height == DEFAULT_END_HEIGHT else None
        periods.append((desc, short, blocks, start, f"{start}-{end_height}", txns))

    # Warmup scan (first scan on a new connection has overhead from
    # precompute table loading in the DuckDB read pool)
    print("Warming up...", end="", flush=True)
    scan(host, port, periods[0][4], tls=tls)
    print(" done.\n")

    results = []
    for desc, short, blocks, start, height_range, txns in periods:
        sys.stdout.write(f"  Scanning {short}...")
        sys.stdout.flush()
        if clients == 1:
            elapsed, count = scan(host, port, height_range, tls=tls)
        else:
            with concurrent.futures.ThreadPoolExecutor(max_workers=clients) as pool:
                futures = [pool.submit(scan, host, port, height_range, tls) for _ in range(clients)]
                thread_results = [f.result() for f in futures]
            elapsed = max(e for e, c in thread_results)
        tps = round(clients * txns / elapsed) if txns and elapsed > 0 else None
        results.append((desc, blocks, start, end_height, txns, elapsed, tps))
        sys.stdout.write(f" {format_time(elapsed)}\n")
        sys.stdout.flush()

    print()
    print_results(results, markdown, clients)


def print_results(results, markdown, clients=1):
    show_txns = any(r[4] is not None for r in results)
    tps_label = f"Transactions/sec ({clients} clients)" if clients > 1 else "Transactions/sec"

    if markdown:
        if show_txns:
            print(f"| | Blocks | Start | End | Transactions | Time | {tps_label} |")
            print("|---|--------|-------|-----|--------------|------|------------------|")
            for desc, blocks, start, end, txns, elapsed, tps in results:
                print(f"| {desc} | {blocks} | {start} | {end} | {format_number(txns)} | {format_time(elapsed)} | {format_number(tps)} |")
        else:
            print("| | Blocks | Start | End | Time |")
            print("|---|--------|-------|-----|------|")
            for desc, blocks, start, end, txns, elapsed, tps in results:
                print(f"| {desc} | {blocks} | {start} | {end} | {format_time(elapsed)} |")
    else:
        if show_txns:
            h = ("", "Blocks", "Start", "End", "Transactions", "Time", tps_label)
            rows = [(desc, str(blocks), str(start), str(end), format_number(txns), format_time(elapsed), format_number(tps)) for desc, blocks, start, end, txns, elapsed, tps in results]
        else:
            h = ("", "Blocks", "Start", "End", "Time")
            rows = [(desc, str(blocks), str(start), str(end), format_time(elapsed)) for desc, blocks, start, end, txns, elapsed, tps in results]

        widths = [max(len(h[i]), max(len(r[i]) for r in rows)) for i in range(len(h))]

        def fmt_row(vals):
            parts = []
            for i, v in enumerate(vals):
                if i == 0:
                    parts.append(f"{v:<{widths[i]}}")
                else:
                    parts.append(f"{v:>{widths[i]}}")
            print("  " + "  ".join(parts))

        fmt_row(h)
        fmt_row(tuple("─" * w for w in widths))
        for r in rows:
            fmt_row(r)


def main():
    parser = argparse.ArgumentParser(
        description="Benchmark Frigate Silent Payments scanning performance.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:\n  python3 benchmark.py\n  python3 benchmark.py --end-height 920000 --markdown\n  python3 benchmark.py --host 192.168.1.10 --port 57001\n  python3 benchmark.py --clients 4\n  python3 benchmark.py --tls --host frigate.example.com --port 50002",
    )
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"server host (default: {DEFAULT_HOST})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"server port (default: {DEFAULT_PORT})")
    parser.add_argument("--end-height", type=int, default=DEFAULT_END_HEIGHT, help=f"end block height (default: {DEFAULT_END_HEIGHT})")
    parser.add_argument("--markdown", action="store_true", help="output as markdown table")
    parser.add_argument("--clients", type=int, default=1, help="number of concurrent clients per scan period (default: 1)")
    parser.add_argument("--periods", type=int, default=0, help="number of periods to run (default: all)")
    parser.add_argument("--tls", action="store_true", help="connect over TLS")
    args = parser.parse_args()

    try:
        run_benchmarks(args.host, args.port, args.end_height, args.markdown, args.clients, args.periods, args.tls)
    except ConnectionRefusedError:
        print(f"Error: could not connect to {args.host}:{args.port}", file=sys.stderr)
        sys.exit(1)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
