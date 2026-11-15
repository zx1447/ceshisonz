#!/usr/bin/env python3
"""CI-only: connect the built .so to a disposable mock gRPC dashboard."""
import ctypes
import pathlib
import queue
import subprocess
import sys
import threading
import time

DUMMY_UUID = b"123e4567-e89b-12d3-a456-426614174000"
DUMMY_SECRET = b"ci-dummy-not-a-real-secret"
mock = subprocess.Popen([sys.argv[1]], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                        text=True, bufsize=1)
lines = queue.Queue()

def collect():
    for line in mock.stdout:
        lines.put(line.rstrip())

threading.Thread(target=collect, daemon=True).start()
library = None
started = False
try:
    ready = lines.get(timeout=20)
    if not ready.startswith("READY 127.0.0.1:"):
        raise RuntimeError("Mock dashboard did not announce a loopback listener")
    address = ready.removeprefix("READY ").encode("ascii")
    library = ctypes.CDLL(str(pathlib.Path(sys.argv[2]).resolve()))
    start = library.StartNezha
    start.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
    start.restype = ctypes.c_int
    stop = library.StopNezha
    stop.argtypes = []
    stop.restype = ctypes.c_int
    result = start(address, DUMMY_SECRET, 0, DUMMY_UUID, 0)
    if result != 0:
        raise RuntimeError(f"Native agent start returned {result}")
    started = True
    expected = {"HOST_OK", "STATE_OK"}
    seen = set()
    deadline = time.monotonic() + 45
    while seen != expected:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"Mock dashboard did not receive all reports: {sorted(seen)}")
        try:
            line = lines.get(timeout=remaining)
        except queue.Empty:
            raise RuntimeError(f"Mock dashboard did not receive all reports: {sorted(seen)}") from None
        if line in expected:
            seen.add(line)
    if stop() != 0:
        raise RuntimeError("Native agent did not stop cleanly after reporting")
    started = False
    print("Mock dashboard verified dummy authentication, host report, state report, and clean stop.")
finally:
    if started and library is not None:
        library.StopNezha()
    mock.terminate()
    try:
        mock.wait(timeout=5)
    except subprocess.TimeoutExpired:
        mock.kill()
        mock.wait()
