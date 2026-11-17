#!/usr/bin/env python3
"""Obfuscate build-time Nezha defaults. This is reversible, not encryption."""
import hashlib
import os
import pathlib
import struct
import sys

MAGIC = b"CZN1"
CONTEXT = b"ceshisonz-builtin-v1"
SALT_LENGTH = 16
MAX_VALUE_BYTES = 1024
MAX_RESOURCE_BYTES = 4096


def _xor_stream(data, salt):
    out = bytearray(len(data))
    for offset in range(0, len(data), 32):
        counter = offset // 32
        mask = hashlib.sha256(CONTEXT + salt + struct.pack(">I", counter)).digest()
        for i, value in enumerate(data[offset:offset + 32]):
            out[offset + i] = value ^ mask[i]
    return bytes(out)


def encode(server, secret):
    if not server or not secret or any(c in server + secret for c in "\x00\r\n"):
        raise ValueError("Both built-in settings must be non-empty single-line values")
    host, separator, port = server.rpartition(":")
    if not separator or not host or not port.isascii() or not port.isdigit() or not 1 <= int(port) <= 65535:
        raise ValueError("The built-in server must include a valid host and port")
    server_bytes = server.encode("utf-8")
    secret_bytes = secret.encode("utf-8")
    if len(server_bytes) > MAX_VALUE_BYTES or len(secret_bytes) > MAX_VALUE_BYTES:
        raise ValueError("A built-in setting exceeds the size limit")
    payload = (struct.pack(">H", len(server_bytes)) + server_bytes
               + struct.pack(">H", len(secret_bytes)) + secret_bytes)
    for _ in range(8):
        salt = os.urandom(SALT_LENGTH)
        result = MAGIC + salt + _xor_stream(payload, salt)
        if server_bytes not in result and secret_bytes not in result:
            return result
    raise ValueError("Could not generate a suitable obfuscated resource")


def decode(data):
    if (len(data) < len(MAGIC) + SALT_LENGTH + 4 or len(data) > MAX_RESOURCE_BYTES
            or data[:len(MAGIC)] != MAGIC):
        raise ValueError("Invalid built-in settings resource")
    salt = data[len(MAGIC):len(MAGIC) + SALT_LENGTH]
    payload = _xor_stream(data[len(MAGIC) + SALT_LENGTH:], salt)
    if len(payload) < 4:
        raise ValueError("Invalid built-in settings payload")
    server_length = struct.unpack_from(">H", payload, 0)[0]
    secret_offset = 2 + server_length
    if not 1 <= server_length <= MAX_VALUE_BYTES or secret_offset + 2 > len(payload):
        raise ValueError("Invalid built-in server length")
    secret_length = struct.unpack_from(">H", payload, secret_offset)[0]
    if not 1 <= secret_length <= MAX_VALUE_BYTES or secret_offset + 2 + secret_length != len(payload):
        raise ValueError("Invalid built-in secret length")
    server = payload[2:secret_offset].decode("utf-8")
    secret = payload[secret_offset + 2:].decode("utf-8")
    return server, secret


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: pack_builtin_defaults.py OUTPUT_FILE")
    server = os.environ.get("BUILTIN_NZ_SERVER", "").strip()
    secret = os.environ.get("BUILTIN_NZ_SECRET", "").strip()
    if not server or not secret:
        raise SystemExit("Built-in Nezha settings are missing from the build environment")
    try:
        resource = encode(server, secret)
        if decode(resource) != (server, secret):
            raise ValueError("Built-in settings round-trip failed")
    except (UnicodeError, ValueError) as error:
        raise SystemExit(str(error)) from None
    output = pathlib.Path(sys.argv[1])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(resource)
    print("Built-in Nezha settings obfuscated; the resulting resource is publicly recoverable.")


if __name__ == "__main__":
    main()
