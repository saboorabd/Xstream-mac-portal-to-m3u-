"""Small helpers shared by the web app and the CLI."""


def kw_list(s, default=""):
    return [k.strip().lower() for k in (s or default).split(",") if k.strip()]


def matches(name, inc, exc):
    n = (name or "").lower()
    return (not inc or any(k in n for k in inc)) and not any(k in n for k in exc)


def esc(s):
    return str(s or "").replace('"', "'").replace("\n", " ").replace("\r", " ")


def build_m3u(items, epg=None):
    """items: dicts with name, url and optional logo, group, tvg_id, chno."""
    head = "#EXTM3U"
    if epg:
        head += f' x-tvg-url="{esc(epg)}"'
    out = [head]
    for i in items:
        attrs = (f'tvg-id="{esc(i.get("tvg_id"))}" tvg-name="{esc(i["name"])}" '
                 f'tvg-logo="{esc(i.get("logo"))}" group-title="{esc(i.get("group"))}"')
        if i.get("chno"):
            attrs = f'tvg-chno="{esc(i["chno"])}" ' + attrs
        out.append(f'#EXTINF:-1 {attrs},{esc(i["name"]).replace(",", " ")}')
        out.append(i["url"])
    return "\n".join(out) + "\n"
