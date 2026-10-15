#!/usr/bin/env python3
"""Find a Reality camouflage site (XRAY_SNI) next to this server. Run ON the server:

    python3 find-sni.py [subnet]        # default: this server's /24

A good candidate is a real site in the same subnet: TLS 1.3 + HTTP/2, a valid
certificate, and — the important part — its domain's DNS actually points at that
IP. Hosting subnets are full of other people's Reality servers impersonating big
brands (cloudflare, amd, twitch, …); those fail the DNS check and are exactly the
pattern DPI blocks. Pick one with real-looking content; Reality contacts it on
every connection, so a dead site means a dead VPN.
"""
import concurrent.futures as cf
import ipaddress
import re
import socket
import ssl
import subprocess
import sys
import urllib.request


def own_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(("1.1.1.1", 53))
    return s.getsockname()[0]


def cert_names(ip):
    ctx = ssl.create_default_context()
    ctx.check_hostname, ctx.verify_mode = False, ssl.CERT_NONE
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    with socket.create_connection((ip, 443), timeout=2) as s, ctx.wrap_socket(s) as t:
        pem = ssl.DER_cert_to_PEM_cert(t.getpeercert(binary_form=True))
    out = subprocess.run(["openssl", "x509", "-noout", "-ext", "subjectAltName"],
                         input=pem, capture_output=True, text=True).stdout
    return [n for n in re.findall(r"DNS:([^,\s]+)", out) if not n.startswith("*.")]


def check(ip):
    ip = str(ip)
    try:
        names = cert_names(ip)
    except Exception:
        return None
    for name in names:
        try:
            ctx = ssl.create_default_context()
            ctx.minimum_version = ssl.TLSVersion.TLSv1_3
            ctx.set_alpn_protocols(["h2", "http/1.1"])
            with socket.create_connection((ip, 443), timeout=3) as s, ctx.wrap_socket(s, server_hostname=name) as t:
                if t.selected_alpn_protocol() != "h2":
                    continue
            resolved = {a[4][0] for a in socket.getaddrinfo(name, 443, socket.AF_INET)}
        except Exception:
            continue
        if ip not in resolved:
            continue  # impersonating someone else — skip
        title = ""
        try:
            req = urllib.request.Request(f"https://{name}/", headers={"User-Agent": "Mozilla/5.0"})
            body = urllib.request.urlopen(req, timeout=5).read(65536).decode("utf-8", "ignore")
            m = re.search(r"<title>([^<]{0,70})", body, re.I)
            title = m.group(1).strip() if m else "(no title)"
        except Exception as e:
            title = f"(HTTP: {getattr(e, 'code', type(e).__name__)})"
        return f"{ip:16s} {name:40s} {title}"
    return None


def main():
    me = own_ip()
    net = ipaddress.ip_network(sys.argv[1] if len(sys.argv) > 1 else f"{me}/24", strict=False)
    print(f"scanning {net} (this server: {me}) — real sites only:", flush=True)
    with cf.ThreadPoolExecutor(64) as ex:
        for line in ex.map(check, (h for h in net.hosts() if str(h) != me)):
            if line:
                print(line, flush=True)


if __name__ == "__main__":
    main()
