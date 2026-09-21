#!/usr/bin/env python3
"""Build, verify and package an update-compatible APK. No signing secrets are printed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "jinhoofkepco/TalkingFamily"
APPLICATION_ID = "kr.family.homeway"


def run(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, **kwargs)


def sdk_path():
    value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not value:
        properties = ROOT / "android/local.properties"
        if properties.exists():
            value = next((line.partition("=")[2].strip() for line in properties.read_text().splitlines()
                          if line.startswith("sdk.dir=")), None)
    if not value or not Path(value).is_dir():
        raise SystemExit("Set ANDROID_HOME to an installed Android SDK.")
    return Path(value)


def previous_release():
    # Explicit repository avoids relying on a checkout's mutable origin configuration.
    releases = json.loads(run("gh", "release", "list", "--repo", REPOSITORY, "--limit", "100",
                              "--json", "tagName,isDraft,isPrerelease", capture_output=True).stdout)
    published = [release for release in releases if not release["isDraft"] and not release["isPrerelease"]]
    if not published:
        return None
    latest = published[0]["tagName"]
    with tempfile.TemporaryDirectory(prefix="talkingfamily-previous-") as directory:
        run("gh", "release", "download", latest, "--repo", REPOSITORY,
            "--pattern", "release-manifest.json", "--dir", directory)
        return json.loads((Path(directory) / "release-manifest.json").read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="Release tag, for example v0.3.1")
    args = parser.parse_args()
    if not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag):
        parser.error("Tag must have the form vX.Y.Z")
    version = args.tag[1:]
    gradle = (ROOT / "android/app/build.gradle.kts").read_text()
    configured_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    configured_code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
    if configured_name != version:
        raise SystemExit("Tag and Android versionName differ. Update the source version first.")
    notes = ROOT / "docs/releases" / f"{version}.md"
    if not notes.is_file():
        raise SystemExit("Release notes are missing: " + str(notes.relative_to(ROOT)))
    expected = (ROOT / "android/signing-certificate.sha256").read_text().strip().lower()
    previous = previous_release()
    if previous:
        if previous["applicationId"] != APPLICATION_ID or previous["certificateSha256"] != expected:
            raise SystemExit("Previous release identity differs; refusing to publish an incompatible update.")
        if configured_code <= previous["versionCode"]:
            raise SystemExit("Every new release must increase versionCode beyond the latest published release.")

    run(str(ROOT / "android/gradlew"), ":app:assembleRelease", ":app:testDebugUnitTest",
        ":app:lintRelease", "--console=plain", cwd=ROOT / "android")
    apk = ROOT / "android/app/build/outputs/apk/release/app-release.apk"
    if not apk.is_file():
        raise SystemExit("Signed release APK was not created.")
    build_tools = sdk_path() / "build-tools/35.0.1"
    signature = run(str(build_tools / "apksigner"), "verify", "--verbose", "--print-certs",
                    str(apk), capture_output=True).stdout
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if [value.lower() for value in certs] != [expected]:
        raise SystemExit("APK certificate mismatch; nothing packaged.")
    badging = run(str(build_tools / "aapt"), "dump", "badging", str(apk), capture_output=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (APPLICATION_ID, str(configured_code), version):
        raise SystemExit("APK package/version mismatch; nothing packaged.")
    if "application-debuggable" in badging:
        raise SystemExit("Refusing to distribute a debuggable APK as the release.")

    output = ROOT / "dist"
    output.mkdir(exist_ok=True)
    filename = f"TalkingFamily-{version}.apk"
    destination = output / filename
    shutil.copy2(apk, destination)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    (output / f"{filename}.sha256").write_text(f"{digest}  {filename}\n")
    manifest = {
        "applicationId": APPLICATION_ID, "versionName": version, "versionCode": configured_code,
        "certificateSha256": expected, "apkSha256": digest, "apkFile": filename,
    }
    (output / "release-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    shutil.copyfile(notes, output / "release-notes.md")
    print(f"Verified {filename}: versionCode={configured_code}, preserved signing certificate.")
    print(f"SHA-256: {digest}")


if __name__ == "__main__":
    main()
