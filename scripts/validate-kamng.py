"""Validate KamNG source identity, routing data and packaged native libraries."""
from pathlib import Path
import argparse
import ipaddress
import json
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "V2rayNG/app"
ANDROID = "{http://schemas.android.com/apk/res/android}"

def validate_source():
    gradle = (APP / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'applicationId = "com.kamyab.kamng"' in gradle, "Wrong application ID"
    assert 'namespace = "com.v2ray.ang"' in gradle, "JNI-compatible namespace changed"
    for flavor, expected in [("main", "com.kamyab.kamng"), ("fdroid", "com.kamyab.kamng.fdroid")]:
        document = ET.parse(APP / f"src/{flavor}/res/xml/shortcuts.xml")
        assert all(item.get(ANDROID + "targetPackage") == expected for item in document.iter("intent"))
    for file in (APP / "src/main/res").glob("values*/strings.xml"):
        document = ET.parse(file)
        app_name = document.find("./string[@name='app_name']")
        assert app_name is not None and app_name.text == "KamNG", str(file)
    config = (APP / "src/main/java/com/v2ray/ang/AppConfig.kt").read_text(encoding="utf-8")
    assert 'const val APP_URL = "$GITHUB_URL/kamyabebrahimi/KamNG"' in config
    assert 'https://api.github.com/repos/kamyabebrahimi/KamNG/releases' in config
    rules = json.loads((APP / "src/main/assets/custom_routing_white_iran").read_text(encoding="utf-8"))
    ids = [rule["id"] for rule in rules]
    assert len(ids) == len(set(ids)) and all(ids), "Rule IDs must be unique"
    allowed = {"id", "remarks", "ip", "domain", "process", "outboundTag", "port", "network", "protocol", "enabled", "locked"}
    for rule in rules:
        assert set(rule) <= allowed, "Unsupported RulesetItem field"
        assert rule["outboundTag"] in {"proxy", "direct", "block"}
        assert type(rule["enabled"]) is bool
        assert not (rule.get("domain") and rule.get("ip")), "Xray AND conditions would break this template"
        for address in rule.get("ip", []):
            if not address.startswith("geoip:"):
                ipaddress.ip_network(address, strict=True)
        for domain in rule.get("domain", []):
            assert domain.startswith(("domain:", "full:", "geosite:")), domain
    ai = next(rule for rule in rules if rule["id"] == "kamng-ai")
    assert "domain:google.com" not in ai["domain"]
    assert "domain:microsoft.com" not in ai["domain"]
    assert "domain:x.com" not in ai["domain"]
    assert "full:gemini.google.com" in ai["domain"]
    assert ids.index("kamng-chatgpt") < ids.index("kamng-ai") < ids.index("kamng-google")
    assert next(rule for rule in rules if rule["id"] == "kamng-ai-support")["enabled"] is False
    telegram = next(rule for rule in rules if rule["id"] == "kamng-telegram-ip")
    assert any(":" in item for item in telegram["ip"]), "Telegram IPv6 missing"
    workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
    assert "branches: [my-releases]" in workflow
    assert "secrets." not in workflow, "Debug build must not require release secrets"
    assert ":app:testPlaystoreDebugUnitTest" in workflow
    assert ":app:testFdroidDebugUnitTest" in workflow
    assert ":app:assemblePlaystoreDebug" in workflow
    assert ":app:assembleFdroidDebug" in workflow
    print(f"PASS: source identity, shortcuts, locale branding and {len(rules)} routing rules")

def validate_apks():
    apks = sorted((APP / "build/outputs/apk").glob("*/debug/*.apk"))
    assert apks, "No APKs produced"
    for apk in apks:
        with zipfile.ZipFile(apk) as archive:
            entries = set(archive.namelist())
        abis = {entry.split("/")[1] for entry in entries if entry.startswith("lib/") and len(entry.split("/")) == 3}
        assert abis, f"No native libraries in {apk.name}"
        required = ["libgojni.so", "libhev-socks5-tunnel.so", "libhevsockstun.so"]
        for abi in abis:
            names = required + (["libaether.so", "libpsiphon-tunnel-core.so", "liblyrebird.so"] if abi != "x86" else [])
            for name in names:
                assert f"lib/{abi}/{name}" in entries, f"{apk.name}: missing {abi}/{name}"
        print(f"PASS: {apk.name}: {', '.join(sorted(abis))}")
    assert {apk.parent.parent.name for apk in apks} == {"playstore", "fdroid"}, "A flavor APK is missing"

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", action="store_true")
    arguments = parser.parse_args()
    validate_source()
    if arguments.apk:
        validate_apks()
