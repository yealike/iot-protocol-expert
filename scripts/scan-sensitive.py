#!/usr/bin/env python3
"""Scan Git snapshots without printing sensitive values. Uses Python standard library only."""
import argparse
import ipaddress
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args])


def findings(raw, path):
    if b"\0" in raw:
        return []
    text = raw.decode("utf-8", "replace")
    result = []
    fixtures = "/src/test/" in path
    examples = [ipaddress.ip_network(n) for n in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")]
    for line_number, line in enumerate(text.splitlines(), 1):
        if re.search(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----", line):
            result.append((line_number, "private-key"))
        if re.search(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{15,})", line):
            result.append((line_number, "access-token"))
        if fixtures:
            continue
        # Bundled icon SVG path coordinates may resemble IPv4 addresses.
        network_line = re.sub(r'\bd:["\'][Mm][^"\']*["\']', 'd:"[SVG]"', line)
        for value in re.findall(r"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])", network_line):
            try:
                address = ipaddress.ip_address(value)
            except ValueError:
                continue
            if address.is_loopback or address.is_unspecified or address.is_multicast or value == "255.255.255.255":
                continue
            if any(address in network for network in examples):
                continue
            # Public cloud metadata endpoints in explicit SSRF deny rules are intentional.
            if address.is_link_local and path.endswith("/McpService.java") and "host.equals(" in line:
                continue
            kind = "server-address"
            if address.packed[0] == 100 and 64 <= address.packed[1] <= 127:
                kind = "shared-network-server-address"
            result.append((line_number, kind))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--staged", action="store_true")
    mode.add_argument("--all-history", action="store_true")
    mode.add_argument("--pre-push", action="store_true")
    args = parser.parse_args()
    if args.all_history or args.pre_push:
        revisions = ["--all"]
        if args.pre_push:
            revisions = [line.split()[1] for line in sys.stdin if len(line.split()) == 4 and set(line.split()[1]) != {"0"}]
            if not revisions:
                return 0
        entries = []
        for line in git("rev-list", "--objects", *revisions).decode("utf-8").splitlines():
            oid, _, path = line.partition(" ")
            entries.append((oid, path or "[commit-or-tag-metadata]"))
    else:
        entries = []
        for entry in git("ls-files", "--stage", "-z").decode("utf-8").split("\0"):
            if entry:
                metadata, path = entry.split("\t", 1)
                entries.append((metadata.split()[1], path))
    errors = 0
    checked = 0
    process = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    try:
        for oid, path in entries:
            process.stdin.write((oid + "\n").encode())
            process.stdin.flush()
            header = process.stdout.readline().split()
            if len(header) != 3:
                raise RuntimeError("Cannot read Git object")
            raw = process.stdout.read(int(header[2]))
            process.stdout.read(1)
            if header[1] not in (b"blob", b"commit", b"tag"):
                continue
            checked += 1
            for line_number, kind in findings(raw, path):
                print(f"{path}:{line_number}: {kind} [value omitted]")
                errors += 1
    finally:
        process.stdin.close()
        process.wait()
    print(f"Sensitive scan: {checked} objects checked, {errors} findings")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
