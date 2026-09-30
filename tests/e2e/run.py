import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
LOCK = json.loads((Path(__file__).parent / "fixtures/dependencies.json").read_text())
CACHE = Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "privatepatches-e2e"


def digest(path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def download(spec):
    algorithm = "sha512" if "sha512" in spec else "sha256"
    expected = spec[algorithm]
    destination = CACHE / (expected + ".jar")
    CACHE.mkdir(parents=True, exist_ok=True)
    if destination.exists() and digest(destination, algorithm) == expected:
        return destination
    request = urllib.request.Request(spec["url"], headers={"User-Agent": "xyqyear/privatepatches-e2e"})
    temporary = destination.with_suffix(f".{os.getpid()}.part")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            if digest(temporary, algorithm) != expected:
                raise RuntimeError(f"Checksum mismatch: {spec['url']}")
            temporary.replace(destination)
            return destination
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2)
    raise AssertionError("download did not complete")


def unused_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def stop_process(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)


def junit(report, path):
    checks = report.get("checks", [])
    suite = ET.Element("testsuite", name="privatepatches-dedicated-server", tests=str(len(checks)),
                       failures=str(sum(not c["passed"] for c in checks)))
    for check in checks:
        case = ET.SubElement(suite, "testcase", name=check["name"], classname=report["mode"])
        if not check["passed"]:
            ET.SubElement(case, "failure", message=check.get("error", "failed")).text = check.get("error", "failed")
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def launch(args, run_dir, launcher, mode):
    report_path = run_dir / f"{mode}.json"
    log_path = run_dir / f"{mode}.log"
    command = [args.java, "-Xms256M", "-Xmx2G", "-XX:ActiveProcessorCount=2",
               f"-Dprivatepatches.e2e.mode={mode}", f"-Dprivatepatches.e2e.cycles={args.cycles}",
               f"-Dprivatepatches.e2e.report={report_path}", "-jar", str(launcher), "nogui"]
    print(f"Starting Minecraft {args.mc} / {args.profile} / {mode}", flush=True)
    started = time.monotonic()
    with log_path.open("w") as output:
        process = subprocess.Popen(command, cwd=run_dir, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            if mode == "client":
                deadline = time.monotonic() + min(args.timeout, 180)
                ready = run_dir / "e2e-ready.json"
                while not ready.exists():
                    if process.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError(f"Dedicated server did not become ready; see {log_path}")
                    time.sleep(0.25)
                port = json.loads(ready.read_text())["port"]
                with (run_dir / "client-process.log").open("w") as client_log:
                    client_environment = dict(os.environ, LIBGL_ALWAYS_SOFTWARE="true", GALLIUM_DRIVER="llvmpipe")
                    client = subprocess.Popen([
                        str(ROOT / "gradlew"), "--no-daemon", "runClientE2e", f"-PmcVersion={args.mc}",
                        f"-Pe2eAddress=127.0.0.1:{port}", f"-PclientReport={run_dir / 'client-result.json'}",
                        "--console=plain"], cwd=ROOT, stdout=client_log, stderr=subprocess.STDOUT,
                        start_new_session=True, env=client_environment)
                    try:
                        client.wait(timeout=args.timeout)
                    finally:
                        stop_process(client)
                    if client.returncode != 0 or not (run_dir / "client-result.json").exists():
                        raise RuntimeError(f"Real-client regression failed; see {run_dir / 'client-process.log'}")
            process.wait(timeout=args.timeout)
        finally:
            stop_process(process)
    if not report_path.exists():
        raise RuntimeError(f"No completed test report (exit={process.returncode}); see {log_path}")
    report = json.loads(report_path.read_text())
    junit(report, run_dir / f"{mode}.xml")
    report["wall_seconds"] = round(time.monotonic() - started, 3)
    if process.returncode != 0 or not report["passed"] or not report["checks"]:
        failed = [c["name"] for c in report["checks"] if not c["passed"]]
        raise RuntimeError(f"Minecraft regression failed: {failed}; see {log_path}")
    print(f"PASS {mode}: {len(report['checks'])} checks, {report['wall_seconds']} s", flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mc", choices=LOCK["profiles"], required=True)
    parser.add_argument("--profile", choices=["minimal", "lithium", "production-mods"], default="minimal")
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--test-jar", type=Path)
    parser.add_argument("--cycles", type=int, default=100)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--java", default="java")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--client", action="store_true")
    args = parser.parse_args()
    if sys.platform != "linux":
        parser.error("Run local Minecraft tests in WSL (Linux), or use the Linux CI runner")
    if args.cycles < 1:
        parser.error("--cycles must be positive")
    if args.profile not in LOCK["profiles"][args.mc]:
        parser.error("The selected dependency profile does not support this Minecraft version")
    if os.environ.get("EULA") != "true":
        parser.error("Set EULA=true after accepting https://aka.ms/MinecraftEULA")
    args.jar = args.jar.resolve(strict=True)
    test_jar = (args.test_jar or ROOT / f"build/libs/privatepatches-e2e-mc{args.mc}.jar").resolve(strict=True)
    suffix = "-client" if args.client else ""
    output_root = (args.output or ROOT / "build/e2e" / f"{args.mc}-{args.profile}{suffix}").resolve()
    run_dir = output_root / f"run-{time.time_ns()}"
    (run_dir / "mods").mkdir(parents=True)
    profile = LOCK["profiles"][args.mc][args.profile]
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
        files = list(executor.map(download, profile))
    launcher = download(LOCK["launchers"][args.mc])
    for entry, path in zip(profile, files):
        (run_dir / "mods" / entry["filename"]).symlink_to(path)
    shutil.copy2(args.jar, run_dir / "mods/privatepatches.jar")
    shutil.copy2(test_jar, run_dir / "mods/privatepatches-e2e.jar")
    (run_dir / "eula.txt").write_text("eula=true\n")
    properties = {
        "server-ip": "127.0.0.1", "server-port": unused_port(), "online-mode": "false",
        "white-list": "false", "enforce-whitelist": "false",
        "enforce-secure-profile": "false", "level-type": "minecraft:flat", "level-seed": 1,
        "generate-structures": "false", "spawn-protection": 0, "view-distance": 2,
        "simulation-distance": 2, "sync-chunk-writes": "false", "allow-flight": "true",
        "pause-when-empty-seconds": 0, "max-players": 30,
        "gamemode": "creative",
        "generator-settings": json.dumps({"biome": "minecraft:plains", "layers": [
            {"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:dirt", "height": 2},
            {"block": "minecraft:grass_block", "height": 1}]}),
    }
    (run_dir / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
    if args.client:
        print("Preparing client classes and assets before starting the dedicated server", flush=True)
        with (run_dir / "client-prepare.log").open("w") as log:
            subprocess.run([str(ROOT / "gradlew"), "--no-daemon", "clientE2eJar", "downloadAssets",
                            f"-PmcVersion={args.mc}", "--console=plain"], cwd=ROOT,
                           stdout=log, stderr=subprocess.STDOUT, timeout=args.timeout, check=True)
        reports = [launch(args, run_dir, launcher, "client")]
    else:
        reports = [launch(args, run_dir, launcher, "regression"), launch(args, run_dir, launcher, "persistence")]
    summary = {"passed": True, "minecraft": args.mc, "profile": args.profile,
               "jar_sha256": digest(args.jar), "loader": LOCK["loader"], "dependencies": profile,
               "cycles": args.cycles, "reports": reports}
    if args.client:
        summary["client"] = json.loads((run_dir / "client-result.json").read_text())
        if not summary["client"].get("passed"):
            raise RuntimeError("Client completed without a passing report")
    (output_root / "result.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"Verified release JAR SHA-256: {summary['jar_sha256']}", flush=True)


if __name__ == "__main__":
    main()
