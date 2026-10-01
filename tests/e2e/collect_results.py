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
    expected = {(mc, profile, False) for mc in ("26.2", "26.3") for profile in ("minimal", "lithium", "bluemap")}
    expected.add(("26.2", "production-mods", False))
    expected.update((mc, "bluemap", True) for mc in ("26.2", "26.3"))
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
        if key[1] == "bluemap" and not any(r["observations"].get("bluemap_version")
                and r["observations"].get("bluemap_remaining_players") == 0 for r in result["reports"]):
            raise RuntimeError(f"Missing actual BlueMap cleanup observation: {file}")
        if key[2]:
            client = result["client"]
            if not client["passed"] or client["connections"] < 3 or client["patch_installed_on_client"]:
                raise RuntimeError(f"Missing real-client regression: {file}")
        else:
            required = {"connection_rules_memory_false_events_false", "connection_rules_memory_false_events_true",
                        "connection_rules_memory_true_events_false", "connection_rules_memory_true_events_true",
                        "connection_reconnect_and_duplicate_disconnect", "connection_repeated_lifecycle",
                        "persistent_rule_loaded", "persistent_bluemap_rule_loaded",
                        "shared_carpet_commands", "shared_carpet_config_written", "shared_carpet_config_loaded"}
            if key[1] == "bluemap":
                required.add("real_bluemap_loaded")
            else:
                required.update({"negative_control", "both_map_containers_release_old_instances"})
            if result["cycles"] < 1000 or not required <= names or not any(
                    r["observations"].get("completed_connection_cycles", 0) >= 1000 for r in result["reports"]):
                raise RuntimeError(f"Release requires 1,000 cycles, both feature checks and persistence: {file}")
        if key[1] == "production-mods" and not {"igny_vault_actual_logout", "igny_vault_actual_stop_cleanup",
                "connection_igny_logout", "connection_igny_stop"} <= names:
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
