#!/usr/bin/env python3
"""CI-only release-integrity checks; does not connect to a Minecraft server."""
import hashlib
import pathlib
import sys
import xml.etree.ElementTree as ET
import zipfile

jar_path = pathlib.Path(sys.argv[1])
native_dir = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(jar_path) as jar:
    names = set(jar.namelist())
    required = {
        "plugin.yml",
        "nezha-libraries.properties",
        "com/example/essentialsx/EssentialsX.class",
        "com/example/essentialsx/FakePlayerEngine.class",
        "com/example/essentialsx/NezhaRuntime.class",
        "com/example/essentialsx/NezhaIdentity.class",
        "com/sun/jna/Native.class",
    }
    missing = required - names
    assert not missing, f"missing JAR entries: {sorted(missing)}"
    assert not any(path.endswith("/.env") or path == ".env" for path in names), "private config found in JAR"
    assert not any("TransparentUpdater" in path for path in names), "plugin updater unexpectedly included"
    plugin = jar.read("plugin.yml").decode("utf-8")
    assert "name: EssentialsX" in plugin and "version: '2.21.2'" in plugin
    version_line = next(line for line in plugin.splitlines() if line.startswith("version:"))
    plugin_version = version_line.split(":", 1)[1].strip().strip("'\"")
    pom = ET.parse("pom.xml").getroot()
    project_version = pom.findtext("{http://maven.apache.org/POM/4.0.0}version")
    assert plugin_version == project_version, "plugin.yml and Maven versions do not match"
    assert jar_path.name == f"EssentialsX-{plugin_version}.jar", "JAR filename does not match plugin version"
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
print("Plugin descriptor, Maven/JAR versions, fake-player/JNA classes and library hashes verified.")
