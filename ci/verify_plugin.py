#!/usr/bin/env python3
"""CI-only release-integrity checks; does not connect to a Minecraft server."""
import hashlib
import os
import pathlib
import sys
import xml.etree.ElementTree as ET
import zipfile

import pack_builtin_defaults

jar_path = pathlib.Path(sys.argv[1])
native_dir = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(jar_path) as jar:
    names = set(jar.namelist())
    required = {
        "plugin.yml",
        "nezha-libraries.properties",
        "ceshisonz-nezha-defaults.bin",
        "com/example/essentialsx/EssentialsX.class",
        "com/example/essentialsx/EmbeddedNezhaConfig.class",
        "com/example/essentialsx/FakePlayerEngine.class",
        "com/example/essentialsx/NezhaRuntime.class",
        "com/example/essentialsx/NezhaIdentity.class",
        "com/sun/jna/Native.class",
    }
    missing = required - names
    assert not missing, f"missing JAR entries: {sorted(missing)}"
    assert not any(path.endswith("/.env") or path == ".env" for path in names), "private config found in JAR"
    assert not any("TransparentUpdater" in path for path in names), "plugin updater unexpectedly included"
    assert not any("JarDisguise" in path for path in names), "JAR disguise unexpectedly included in no-disguise build"
    plugin = jar.read("plugin.yml").decode("utf-8")
    assert "name: EssentialsX" in plugin and "version: '2.21.2'" in plugin
    version_line = next(line for line in plugin.splitlines() if line.startswith("version:"))
    plugin_version = version_line.split(":", 1)[1].strip().strip("'\"")
    pom = ET.parse("pom.xml").getroot()
    project_version = pom.findtext("{http://maven.apache.org/POM/4.0.0}version")
    assert plugin_version == project_version, "plugin.yml and Maven versions do not match"
    assert jar_path.name == f"EssentialsX-{plugin_version}.jar", "JAR filename does not match plugin version"
    expected_server = os.environ.get("BUILTIN_NZ_SERVER", "").strip()
    expected_secret = os.environ.get("BUILTIN_NZ_SECRET", "").strip()
    assert expected_server and expected_secret, "Build-time defaults are not available for verification"
    decoded = pack_builtin_defaults.decode(jar.read("ceshisonz-nezha-defaults.bin"))
    assert decoded == (expected_server, expected_secret), "Built-in defaults do not match the build environment"
    host = expected_server.rsplit(":", 1)[0]
    sensitive = [expected_server.encode(), expected_secret.encode()]
    if len(host) >= 8:
        sensitive.append(host.encode())
    for name in names:
        if name.endswith("/"):
            continue
        data = jar.read(name)
        assert not any(value in data for value in sensitive), f"Plaintext built-in value in JAR entry: {name}"
    props = {}
    for line in jar.read("nezha-libraries.properties").decode("utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            props[key] = value
    for arch in ("amd64", "arm64"):
        path = native_dir / f"nezha_agent_linux_{arch}.so"
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert props[f"linux.{arch}.sha256"] == digest, f"{arch} library digest mismatch"
        assert props[f"linux.{arch}.url"].endswith("/" + path.name), f"{arch} URL mismatch"
print("Plugin versions, built-in obfuscation, fake-player/JNA classes and library hashes verified.")
