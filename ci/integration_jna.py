#!/usr/bin/env python3
"""CI-only: packaged Java UUID -> JNA -> native agent -> loopback mock dashboard."""
import os
import pathlib
import queue
import subprocess
import sys
import tempfile
import threading
import time

mock_path, native_path, jar_path, classes_path = sys.argv[1:]
mock = subprocess.Popen([mock_path], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, bufsize=1)
mock_lines = queue.Queue()
java_lines = []
java = None
fixture_dir = None


def collect_mock():
    for line in mock.stdout:
        mock_lines.put(line.rstrip())


def collect_java():
    for line in java.stdout:
        java_lines.append(line.rstrip())


threading.Thread(target=collect_mock, daemon=True).start()
try:
    ready = mock_lines.get(timeout=20)
    if not ready.startswith("READY 127.0.0.1:"):
        raise RuntimeError("Mock dashboard did not announce a loopback listener")
    address = ready.removeprefix("READY ")
    environment = os.environ.copy()
    for name in ("NEZHA_UUID", "NZ_UUID", "NEZHA_IDENTITY_IP", "NZ_IDENTITY_IP",
                 "BUILTIN_NZ_SERVER", "BUILTIN_NZ_SECRET"):
        environment.pop(name, None)
    fixture_dir = tempfile.TemporaryDirectory(prefix="ceshisonz-builtin-fixture-", dir=os.getenv("RUNNER_TEMP"))
    fixture = pathlib.Path(fixture_dir.name) / "dummy-defaults.bin"
    fixture_env = environment.copy()
    fixture_env.update({"BUILTIN_NZ_SERVER": address, "BUILTIN_NZ_SECRET": "ci-dummy-not-a-real-secret"})
    generator = pathlib.Path(__file__).with_name("pack_builtin_defaults.py")
    result = subprocess.run([sys.executable, str(generator), str(fixture)], env=fixture_env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=15)
    if result.returncode:
        raise RuntimeError("Dummy built-in configuration fixture could not be generated")
    java = subprocess.Popen(["java", "-cp", jar_path + os.pathsep + classes_path,
                             "com.example.essentialsx.NativeSmoke", native_path, address, str(fixture)],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1, env=environment)
    collector = threading.Thread(target=collect_java, daemon=True)
    collector.start()
    expected = {"HOST_OK", "STATE_OK"}
    seen = set()
    deadline = time.monotonic() + 50
    while seen != expected:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"Mock dashboard did not receive all JNA reports: {sorted(seen)}")
        if java.poll() is not None:
            raise RuntimeError("JNA process stopped before reporting: " + " | ".join(java_lines))
        try:
            line = mock_lines.get(timeout=min(1, remaining))
        except queue.Empty:
            continue
        if line in expected:
            seen.add(line)
    java.stdin.write("\n")
    java.stdin.flush()
    java.stdin.close()
    if java.wait(timeout=25) != 0:
        raise RuntimeError("JNA process failed: " + " | ".join(java_lines))
    collector.join(timeout=2)
    if "JNA_STARTED" not in java_lines or "JNA_STOPPED" not in java_lines:
        raise RuntimeError("JNA startup/stop confirmation missing")
    print("Dummy embedded defaults and IP-derived UUID passed JNA, mock reporting and clean stop.")
finally:
    if java is not None and java.poll() is None:
        java.terminate()
        try:
            java.wait(timeout=5)
        except subprocess.TimeoutExpired:
            java.kill()
            java.wait()
    mock.terminate()
    try:
        mock.wait(timeout=5)
    except subprocess.TimeoutExpired:
        mock.kill()
        mock.wait()
    if fixture_dir is not None:
        fixture_dir.cleanup()
