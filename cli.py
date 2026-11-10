#!/usr/bin/env python3
"""Terminal / Termux version: MAC portal -> M3U file with real, resolved stream links.

  One portal:
    python cli.py --url http://host/stalker_portal/c/ --mac 00:1A:79:XX:XX:XX \
                  --sn SERIAL --d1 DEVICEID --d2 DEVICEID -o playlist.m3u

  Many portals (copy portals.example.json -> portals.json and edit):
    python cli.py --config portals.json --outdir ~/storage/shared

Needs only:  pip install requests
"""
import argparse
import json
import os
import sys
import time
from concurrent.futures import ThreadPoolExecutor

from m3u import build_m3u, kw_list, matches
from stalker import Portal, PortalError, clean_cmd


def run(cfg, args, out_path):
    name = cfg.get("name") or cfg["url"]
    print(f"\n===== {name} =====")
    portal = Portal(cfg["url"], cfg["mac"], cfg.get("d1", ""), cfg.get("d2", ""), cfg.get("sn", ""),
                    proxy=args.proxy or None)

    print("[1/4] Handshake + profile ...")
    portal.login()
    print(f"      OK  (endpoint: {portal.ep})")
    if portal.profile_error:
        print("      profile warning:", portal.profile_error)
    exp = portal.account_info().get("expires")
    if exp:
        print("      account expires:", exp)

    print("[2/4] Channel list ...")
    inc, exc = kw_list(args.keywords), kw_list(args.exclude)
    items = [c for c in portal.items() if matches(c["group"], inc, exc)]
    print(f"      {len(items)} channels after filters")
    if not items:
        print("      nothing to write")
        return 0

    def resolve(c):
        if args.no_resolve:
            return clean_cmd(c["cmd"])
        for attempt in range(3):
            try:
                return portal.link(c["cmd"])
            except PortalError:
                time.sleep(0.5 * (attempt + 1))
        return ""

    print("[3/4] Resolving stream links" + (" (skipped)" if args.no_resolve else " (takes a while) ..."))
    done = 0
    results = []
    with ThreadPoolExecutor(max_workers=max(1, args.threads)) as ex:
        for url in ex.map(resolve, items):
            results.append(url)
            done += 1
            if done % 100 == 0:
                print(f"      {done}/{len(items)}")

    good = []
    for c, url in zip(items, results):
        if url:
            c["url"] = url
            good.append(c)
    if args.sort:
        good.sort(key=lambda i: (i["group"].lower(), i["name"].lower()))

    print("[4/4] Writing M3U ...")
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(build_m3u(good, args.epg or None))
    print(f"Done! {len(good)} channels ({len(items) - len(good)} failed) -> {out_path}")
    return len(good)


def main():
    ap = argparse.ArgumentParser(description="MAC (Stalker) portal -> M3U")
    ap.add_argument("--url"), ap.add_argument("--mac"), ap.add_argument("--sn", default="")
    ap.add_argument("--d1", default=""), ap.add_argument("--d2", default="")
    ap.add_argument("--config", help="JSON file with a list of portals")
    ap.add_argument("--only", type=int, help="with --config: run only portal number N (1-based)")
    ap.add_argument("-o", "--output", help="output file (single portal)")
    ap.add_argument("--outdir", default=".", help="output folder, e.g. ~/storage/shared")
    ap.add_argument("--keywords", default="", help="only groups containing these words, e.g. tamil,sports")
    ap.add_argument("--exclude", default="", help="skip groups containing these words")
    ap.add_argument("--epg", default="", help="XMLTV url to put in the playlist header")
    ap.add_argument("--no-sort", dest="sort", action="store_false", help="keep portal order")
    ap.add_argument("--no-resolve", action="store_true",
                    help="do not call create_link (only works for portals with direct links)")
    ap.add_argument("--proxy", default="", help="optional http://user:pass@host:port")
    ap.add_argument("--threads", type=int, default=8)
    args = ap.parse_args()

    if args.config:
        with open(os.path.expanduser(args.config), encoding="utf-8") as f:
            portals = json.load(f)
        if args.only:
            portals = [portals[args.only - 1]]
    elif args.url and args.mac:
        portals = [{"name": "portal", "url": args.url, "mac": args.mac, "sn": args.sn,
                    "d1": args.d1, "d2": args.d2, "output": args.output}]
    else:
        ap.error("give --url and --mac, or --config portals.json")

    outdir = os.path.expanduser(args.outdir)
    os.makedirs(outdir, exist_ok=True)
    total = 0
    for i, cfg in enumerate(portals, 1):
        fname = cfg.get("output") or f"{cfg.get('name') or 'portal' + str(i)}.m3u"
        out = os.path.expanduser(args.output) if args.output and len(portals) == 1 \
            else os.path.join(outdir, fname)
        try:
            total += run(cfg, args, out)
        except PortalError as e:
            print(f"FAILED: {e}")
        except Exception as e:  # keep going with the next portal
            print(f"FAILED: {e.__class__.__name__}: {e}")
    sys.exit(0 if total else 1)


if __name__ == "__main__":
    main()
