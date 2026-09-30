import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    jars = list(args.candidate.glob("*.jar"))
    if len(jars) != 1:
        raise RuntimeError("Exactly one frozen release JAR is required")
    jar = jars[0]
    checksum = hashlib.sha256(jar.read_bytes()).hexdigest()
    expected = {(mc, profile, False) for mc in ("26.2", "26.3") for profile in ("minimal", "lithium")}
    expected.add(("26.2", "production-mods", False))
    expected.update((mc, "minimal", True) for mc in ("26.2", "26.3"))
    found = {}
    for file in args.results.rglob("result.json"):
        result = json.loads(file.read_text())
        key = (result["minecraft"], result["profile"], "client" in result)
        if key not in expected:
            continue
        if key in found:
            raise RuntimeError(f"Duplicate result for {key}")
        if not result["passed"] or result["jar_sha256"] != checksum:
            raise RuntimeError(f"Failed or different-artifact result: {file}")
        for report in result["reports"]:
            if not report["passed"] or not report["checks"] or not all(c["passed"] for c in report["checks"]):
                raise RuntimeError(f"Incomplete test report: {file}")
        names = {c["name"] for report in result["reports"] for c in report["checks"]}
        if key[2]:
            client = result["client"]
            if not client["passed"] or client["connections"] < 3 or client["patch_installed_on_client"]:
                raise RuntimeError(f"Missing real-client regression: {file}")
        elif result["cycles"] < 1000 or not {"negative_control", "both_map_containers_release_old_instances", "persistent_rule_loaded"} <= names:
            raise RuntimeError(f"Release requires 1,000 cycles and persistence checks: {file}")
        if key[1] == "production-mods" and not {"igny_vault_actual_logout", "igny_vault_actual_stop_cleanup"} <= names:
            raise RuntimeError("The production-mods profile must exercise the actual Igny vault paths")
        found[key] = result
    if set(found) != expected:
        raise RuntimeError(f"Missing required matrix results: {sorted(expected - set(found))}")
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
    manifest = {"schema_version": 1, "version": metadata["version"], "commit": os.getenv("GITHUB_SHA"),
                "verified_at": datetime.now(timezone.utc).isoformat(), "jar": jar.name, "sha256": checksum,
                "results": [found[key] for key in sorted(found)]}
    args.output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, args.output / jar.name)
    (args.output / "compatibility.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (args.output / "SHA256SUMS").write_text(f"{checksum}  {jar.name}\n")
    print(f"Release verified: {jar.name}; {len(found)} matrix results; SHA-256 {checksum}")


if __name__ == "__main__":
    main()
