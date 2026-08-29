"""Talks to the game panel the server runs on, so a build can be put on it without going through the web UI.

Credentials come from a .env at the repository root and are never printed:

    PANEL_KEY   a Pterodactyl client key, the ptlc_ kind
    PANEL_URL   the panel address; the server page URL copied from the browser is fine
    SERVER_ID   the server's id; the full uuid is fine

Both URL and id are accepted in whatever form the panel shows them, because that is what anyone actually has to
hand. The API wants the bare origin and the first block of the uuid, and works that out here rather than asking
for the file to be written in a shape nobody would guess.

    python tools/panel.py status
    python tools/panel.py mods                     # every MTR jar the server has
    python tools/panel.py install <version>        # pull that version from the archive, remove the old one
    python tools/panel.py power <start|stop|restart>
    python tools/panel.py log [lines]              # the tail of the server's own log

`install` pulls from the public archive rather than uploading, so a sixty megabyte jar crosses the internet once,
between two servers, instead of twice through here. It refuses to leave two MTR jars in place: Fabric would load
both and the failure that follows is not obvious from the log.
"""

import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ARCHIVE = "https://archive.netartisan.site/archive/mods"
JAR = "Minecraft_Transit_Railway_1.19.4_%s.jar"


def load_env():
    path = os.path.join(ROOT, ".env")
    if not os.path.isfile(path):
        raise SystemExit("no .env at %s -- it needs PANEL_KEY, PANEL_URL and SERVER_ID" % path)

    env = {}
    with io.open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            env[key.strip()] = value.strip().strip('"').strip("'")

    missing = [k for k in ("PANEL_KEY", "PANEL_URL", "SERVER_ID") if not env.get(k)]
    if missing:
        raise SystemExit(".env is missing: %s" % ", ".join(missing))

    # The panel address as pasted is usually the page for one server; the API lives at the site root
    parsed = urllib.parse.urlsplit(env["PANEL_URL"])
    env["PANEL_URL"] = "%s://%s" % (parsed.scheme or "https", parsed.netloc or parsed.path.split("/")[0])
    # The client API identifies a server by the first block of its uuid, which is what the panel calls the id
    env["SERVER_ID"] = env["SERVER_ID"].split("-")[0]
    return env


def call(env, method, path, body=None, raw=False):
    url = "%s/api/client/servers/%s%s" % (env["PANEL_URL"], env["SERVER_ID"], path)
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", "Bearer %s" % env["PANEL_KEY"])
    request.add_header("Accept", "application/json")
    if data:
        request.add_header("Content-Type", "application/json")

    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            payload = response.read()
    except urllib.error.HTTPError as error:
        # The body carries the reason; the status alone rarely says which of several things went wrong
        raise SystemExit("panel said %s for %s %s: %s" % (error.code, method, path, error.read().decode()[:400]))

    if raw:
        return payload
    return json.loads(payload) if payload.strip() else {}


def mtr_jars(env):
    listing = call(env, "GET", "/files/list?directory=%2Fmods")
    return [f["attributes"] for f in listing.get("data", [])
            if f["attributes"]["name"].startswith("Minecraft_Transit_Railway")]


def status(env):
    attributes = call(env, "GET", "/resources")["attributes"]
    resources = attributes["resources"]
    print("state   %s" % attributes.get("current_state"))
    print("cpu     %.1f%%" % resources.get("cpu_absolute", 0))
    print("memory  %.2f GB" % (resources.get("memory_bytes", 0) / 1073741824))
    print("disk    %.2f GB" % (resources.get("disk_bytes", 0) / 1073741824))
    print("uptime  %.1f min" % (resources.get("uptime", 0) / 60000))


def mods(env):
    for jar in mtr_jars(env):
        print("%-52s %12d  %s" % (jar["name"], jar["size"], jar.get("modified_at", "")))


def install(env, version):
    wanted = JAR % version
    before = mtr_jars(env)
    if any(jar["name"] == wanted for jar in before):
        print("%s is already there" % wanted)
    else:
        print("pulling %s from the archive..." % wanted)
        call(env, "POST", "/files/pull", {"root": "/mods", "url": "%s/%s" % (ARCHIVE, wanted)})

        # The pull is accepted immediately and happens in its own time, so wait for the file rather than assume
        for _ in range(60):
            time.sleep(5)
            if any(jar["name"] == wanted for jar in mtr_jars(env)):
                break
        else:
            raise SystemExit("%s never arrived; nothing was removed" % wanted)

    stale = [jar["name"] for jar in mtr_jars(env) if jar["name"] != wanted]
    if stale:
        print("removing %s" % ", ".join(stale))
        call(env, "POST", "/files/delete", {"root": "/mods", "files": stale})

    remaining = mtr_jars(env)
    for jar in remaining:
        print("in place: %s  %d bytes" % (jar["name"], jar["size"]))
    if len(remaining) != 1:
        raise SystemExit("expected exactly one MTR jar, found %d -- Fabric would load them all" % len(remaining))


def power(env, signal):
    if signal not in ("start", "stop", "restart", "kill"):
        raise SystemExit("power takes start, stop, restart or kill")
    call(env, "POST", "/power", {"signal": signal})
    print("sent %s" % signal)


def log(env, lines):
    text = call(env, "GET", "/files/contents?file=%2Flogs%2Flatest.log", raw=True).decode("utf-8", "replace")
    for line in text.splitlines()[-lines:]:
        print(line)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2

    env = load_env()
    command = argv[1]

    if command == "status":
        status(env)
    elif command == "mods":
        mods(env)
    elif command == "install":
        if len(argv) < 3:
            raise SystemExit("install needs a version, e.g. 3.5.04")
        install(env, argv[2])
    elif command == "power":
        power(env, argv[2] if len(argv) > 2 else "")
    elif command == "log":
        log(env, int(argv[2]) if len(argv) > 2 else 40)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
