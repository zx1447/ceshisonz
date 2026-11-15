#!/usr/bin/env python3
"""CI-only: packaged Java UUID -> JNA -> native agent -> loopback mock dashboard."""
import os
import queue
import subprocess
import sys
import threading
import time

mock_path, native_path, jar_path, classes_path = sys.argv[1:]
mock = subprocess.Popen([mock_path], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, bufsize=1)
mock_lines = queue.Queue()
java_lines = []
java = None


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
    for name in ("NEZHA_UUID", "NZ_UUID", "NEZHA_IDENTITY_IP", "NZ_IDENTITY_IP"):
        environment.pop(name, None)
    java = subprocess.Popen(["java", "-cp", jar_path + os.pathsep + classes_path,
                             "com.example.essentialsx.NativeSmoke", native_path, address],
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
    print("Packaged IP-derived UUID passed JNA, dummy authentication, reporting and clean stop.")
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
