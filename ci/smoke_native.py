#!/usr/bin/env python3
"""CI-only smoke check: dummy credentials, no real dashboard or server."""
import ctypes
import pathlib
import sys
import time
import uuid

library = ctypes.CDLL(str(pathlib.Path(sys.argv[1]).resolve()))
start = library.StartNezha
start.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_int]
start.restype = ctypes.c_int
stop = library.StopNezha
stop.argtypes = []
stop.restype = ctypes.c_int
identifier = str(uuid.uuid4()).encode("ascii")

assert start(b"", b"dummy", 1, identifier, 0) == 1, "empty address must be rejected"
for cycle in range(2):
    result = start(b"127.0.0.1:1", b"ci-dummy-not-a-real-secret", 0, identifier, 0)
    assert result == 0, f"start rejected in cycle {cycle}: {result}"
    assert start(b"127.0.0.1:1", b"dummy", 0, identifier, 0) == 2, "duplicate start must be rejected"
    time.sleep(1)
    result = stop()
    assert result == 0, f"stop timed out in cycle {cycle}: {result}"
    assert stop() == 0, "stop should be idempotent"
print("Native start/duplicate/stop/restart checks passed with dummy data; dashboard connectivity not tested.")
