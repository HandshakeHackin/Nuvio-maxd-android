#!/usr/bin/env python3
"""Verify a signed Android TV APK and generate its independent stable feed."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess

APPLICATION_ID = "io.github.handshakehackin.nuviomaxd.androidtv"
REPOSITORY = "HandshakeHackin/Nuvio-maxd-android"


def prepare(apk, aapt, apksigner, output, properties):
    values = dict(line.strip().split("=", 1) for line in properties.read_text().splitlines()
                  if line.strip() and not line.lstrip().startswith("#"))
    version = values["VERSION_NAME"]
    code = int(values["VERSION_CODE"])
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version) or not 1 <= code <= 2_100_000_000:
        raise ValueError("Invalid Android version.properties")
    subprocess.run([str(apksigner), "verify", "--verbose", str(apk)], check=True)
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.M)
    if not package or package.groups() != (APPLICATION_ID, str(code), version):
        raise ValueError("APK package/version does not match the Android release")
    if not sdk or int(sdk[1]) < 26:
        raise ValueError("Invalid Android TV minimum SDK")
    size = apk.stat().st_size
    if not 1 <= size <= 256 * 1024 * 1024:
        raise ValueError("APK exceeds the updater size limit")
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    name = f"NuvioMaxd-AndroidTV-{version}.apk"
    tag = f"android-tv-v{version}"
    feed = {
        "schemaVersion": 1, "platform": "android-tv", "applicationId": APPLICATION_ID,
        "channel": "stable", "latest": {
            "versionCode": code, "versionName": version, "minSdk": int(sdk[1]),
            "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/{tag}/{name}",
            "sha256": digest, "sizeBytes": size,
            "notes": "See this Android TV release on GitHub for changes and known limitations.",
        },
    }
    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(apk, output / name)
    (output / "android-tv-stable.json").write_text(json.dumps(feed, indent=2) + "\n")
    (output / "SHA256SUMS").write_text(f"{digest}  {name}\n")
    print(f"Prepared {tag} (Android versionCode {code})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("apk", "aapt", "apksigner", "output"):
        parser.add_argument("--" + option, type=pathlib.Path, required=True)
    parser.add_argument("--properties", type=pathlib.Path, default=pathlib.Path("version.properties"))
    args = parser.parse_args()
    prepare(args.apk, args.aapt, args.apksigner, args.output, args.properties)
