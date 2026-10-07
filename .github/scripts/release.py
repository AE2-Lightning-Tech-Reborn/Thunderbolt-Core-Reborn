"""Release metadata and dependency staging for GitHub Actions (standard library only)."""

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import xml.etree.ElementTree as ET
from zipfile import ZipFile


PROJECTS = {
    "ae2lt": ("AE2-Lightning-Tech-Reborn", "com/moakiee/ae2lt/AE2LightningTech.class"),
    "thunderbolt": ("Thunderbolt-Core-Reborn", "com/moakiee/thunderbolt/ThunderboltCore.class"),
    "ae2ltpp": ("AE2LT-Packaged-Pattern-Provider-Reborn", "com/moakiee/ae2lt/packaged/AE2LTPackagedProvider.class"),
}
VERSION_PATTERN = r"([0-9]+\.[0-9]+\.[0-9]+(?:[.-][0-9A-Za-z]+)*)"


def properties(root):
    result = {}
    for line in (root / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def metadata(props, tag, prerelease):
    game = props["minecraft_version"]
    if game == "1.21.1":
        prefix, loader, java, metadata_path = "v", "neoforge", "21", "META-INF/neoforge.mods.toml"
    elif game == "1.20.1":
        prefix, loader, java, metadata_path = "forge-1.20.1-v", "forge", "17", "META-INF/mods.toml"
    else:
        raise ValueError(f"Unsupported Minecraft release target: {game}")
    match = re.fullmatch(re.escape(prefix) + VERSION_PATTERN, tag)
    if not match:
        raise ValueError(f"Minecraft {game} releases require tag {prefix}<version>; received: {tag}")
    version = match[1]
    if re.search(r"(?:^|[.-])alpha[0-9]*(?:[.-]|$)", version, re.I):
        release_type = "alpha"
    elif re.search(r"(?:^|[.-])beta[0-9]*(?:[.-]|$)", version, re.I) or prerelease:
        release_type = "beta"
    else:
        release_type = "release"
    artifact = props.get("artifact_name", props.get("mod_id", "mod"))
    jar_base = f"{artifact}-forge-1.20.1" if loader == "forge" else artifact
    return {
        "version": version,
        "platform_version": f"{version}-forge.1.20.1" if loader == "forge" else version,
        "release_type": release_type,
        "game_version": game,
        "java_version": java,
        "mod_loader": loader,
        "metadata_path": metadata_path,
        "artifact_id": jar_base,
        "jar_file": f"{jar_base}-{version}.jar",
    }


def dependency_versions(props):
    result = {}
    if props["mod_id"] == "ae2ltpp":
        ae2lt = props.get("ae2lt_version")
        if not ae2lt:
            notation = props.get("ae2lt_maven_notation", "")
            if notation:
                ae2lt = notation.rsplit(":", 1)[-1]
            else:
                name = Path(props.get("ae2lt_jar") or props.get("ae2lt_local_jar", "")).name
                match = re.fullmatch(r"ae2lt-" + VERSION_PATTERN + r"\.jar", name)
                if not match:
                    raise ValueError("Cannot determine the AE2LT dependency version from gradle.properties")
                ae2lt = match[1]
        result["ae2lt"] = ae2lt
    if props["mod_id"] != "thunderbolt":
        thunderbolt = props.get("thunderbolt_version")
        if not thunderbolt:
            thunderbolt = props.get("thunderbolt_maven_notation", "").rsplit(":", 1)[-1]
        result["thunderbolt"] = thunderbolt
    for project, version in result.items():
        if not re.fullmatch(VERSION_PATTERN, version or ""):
            raise ValueError(f"Invalid {project} dependency version: {version!r}")
    return result


def validate_jar(path, mod_id, version, metadata_path):
    with ZipFile(path) as jar:
        if PROJECTS[mod_id][1] not in jar.namelist():
            raise ValueError(f"Missing {mod_id} entry point in {path.name}")
        contents = tomllib.loads(jar.read(metadata_path).decode("utf-8"))
        if not any(mod.get("modId") == mod_id and mod.get("version") == version
                   for mod in contents.get("mods", [])):
            raise ValueError(f"Unexpected {mod_id} version in {path.name}; expected {version}")


def stage_maven(jar_path, repository, group, artifact, version):
    destination = repository / group.replace(".", "/") / artifact / version
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(jar_path, destination / f"{artifact}-{version}.jar")
    pom = ET.Element("project", xmlns="http://maven.apache.org/POM/4.0.0")
    for name, value in (("modelVersion", "4.0.0"), ("groupId", group),
                        ("artifactId", artifact), ("version", version)):
        ET.SubElement(pom, name).text = value
    ET.ElementTree(pom).write(destination / f"{artifact}-{version}.pom", encoding="utf-8", xml_declaration=True)


def download_dependencies(root, props, owner):
    game = props["minecraft_version"]
    forge = game == "1.20.1"
    metadata_path = "META-INF/mods.toml" if forge else "META-INF/neoforge.mods.toml"
    downloads = root / "release-dependencies"
    repository = downloads / "maven"
    downloads.mkdir(parents=True, exist_ok=True)
    outputs = {}
    for mod_id, version in dependency_versions(props).items():
        file_base = f"{mod_id}-forge-1.20.1" if forge else mod_id
        file_name = f"{file_base}-{version}.jar"
        tag = f"forge-1.20.1-v{version}" if forge else f"v{version}"
        subprocess.run(["gh", "release", "download", tag, "--repo", f"{owner}/{PROJECTS[mod_id][0]}",
                        "--pattern", file_name, "--dir", str(downloads)], check=True)
        jar_path = downloads / file_name
        validate_jar(jar_path, mod_id, version, metadata_path)
        artifact = props.get("thunderbolt_artifact_id", "thunderbolt") if mod_id == "thunderbolt" else mod_id
        group = f"com.moakiee.{mod_id}"
        stage_maven(jar_path, repository, group, artifact, version)
        outputs[f"{mod_id}_maven_notation"] = f"{group}:{artifact}:{version}"
    return outputs


def prepare_artifacts(root, props, release):
    jars = sorted(path for path in (root / "build/libs").glob("*.jar")
                  if not path.name.endswith(("-sources.jar", "-javadoc.jar", "-slim.jar")))
    if len(jars) != 1:
        raise ValueError(f"Expected exactly one distributable JAR, found {len(jars)}: {jars}")
    validate_jar(jars[0], props["mod_id"], release["version"], release["metadata_path"])
    output = root / "release-artifacts"
    output.mkdir(exist_ok=True)
    destination = output / jars[0].name
    shutil.copyfile(jars[0], destination)
    digest = hashlib.sha256(destination.read_bytes()).hexdigest()
    (output / "SHA256SUMS").write_text(f"{digest}  {destination.name}\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("metadata", "dependencies", "artifacts"))
    args = parser.parse_args()
    root = Path.cwd()
    props = properties(root)
    release = metadata(props, os.environ["TAG"], os.environ.get("PRERELEASE") == "true")
    outputs = {}
    if args.command == "metadata":
        outputs = release
    elif args.command == "dependencies":
        outputs = download_dependencies(root, props, os.environ["RELEASE_OWNER"])
    else:
        prepare_artifacts(root, props, release)
    if outputs:
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in outputs.items():
                output.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
