"""Exercise the release contract without uploading or needing platform tokens."""

import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
from zipfile import ZipFile

import release


def jar_fixture(path, mod_id, version, forge=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(path, "w") as jar:
        jar.writestr(release.PROJECTS[mod_id][1], b"fixture")
        jar.writestr("META-INF/mods.toml" if forge else "META-INF/neoforge.mods.toml",
                     f'[[mods]]\nmodId="{mod_id}"\nversion="{version}"\n')


class ReleaseContractTest(unittest.TestCase):
    def test_release_types_and_loader_targets(self):
        cases = (
            ("2.1.2", False, "release"),
            ("2.1.2", True, "beta"),
            ("2.1.2-beta", False, "beta"),
            ("2.1.2-beta.1", True, "beta"),
            ("2.1.2-BETA2", False, "beta"),
            ("2.1.2-alpha.1", True, "alpha"),
            ("2.1.2-alpha1", False, "alpha"),
            ("2.1.2-alphabet", False, "release"),
            ("2.1.2-rc.1", True, "beta"),
        )
        for game, prefix, loader, java in (("1.21.1", "v", "neoforge", "21"),
                                           ("1.20.1", "forge-1.20.1-v", "forge", "17")):
            for version, prerelease, expected_type in cases:
                with self.subTest(game=game, version=version, prerelease=prerelease):
                    result = release.metadata({"minecraft_version": game}, prefix + version, prerelease)
                    self.assertEqual((result["version"], result["release_type"], result["mod_loader"], result["java_version"]),
                                     (version, expected_type, loader, java))
                    self.assertEqual(result["platform_version"], version + ("-forge.1.20.1" if loader == "forge" else ""))

    def test_rejects_wrong_loader_tag_and_invalid_version(self):
        for game, tag in (("1.20.1", "v2.0.0"), ("1.21.1", "forge-1.20.1-v2.0.0"),
                          ("1.21.1", "v2.0"), ("1.21.1", "v2.0.0\nextra"), ("1.19.2", "v2.0.0")):
            with self.subTest(game=game, tag=tag), self.assertRaises(ValueError):
                release.metadata({"minecraft_version": game}, tag, False)

    def test_provider_versions_come_from_current_build_configuration(self):
        props = {"mod_id": "ae2ltpp", "ae2lt_jar": "../AE2-Lightning-Tech/build/libs/ae2lt-2.1.0-beta.1.jar",
                 "thunderbolt_maven_notation": "com.moakiee.thunderbolt:thunderbolt:2.0.0"}
        self.assertEqual(release.dependency_versions(props), {"ae2lt": "2.1.0-beta.1", "thunderbolt": "2.0.0"})
        props.update(ae2lt_version="2.1.1", thunderbolt_version="2.0.1")
        self.assertEqual(release.dependency_versions(props), {"ae2lt": "2.1.1", "thunderbolt": "2.0.1"})
        props["thunderbolt_version"] = "../other"
        with self.assertRaises(ValueError):
            release.dependency_versions(props)

    def test_downloads_correct_release_and_stages_forge_and_neoforge_coordinates(self):
        for game in ("1.21.1", "1.20.1"):
            for mod_id in ("ae2lt", "ae2ltpp"):
                with self.subTest(game=game, mod_id=mod_id), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    forge = game == "1.20.1"
                    props = {"minecraft_version": game, "mod_id": mod_id,
                             "ae2lt_version": "2.1.0-beta.1", "thunderbolt_version": "2.0.1"}
                    if forge and mod_id == "ae2lt":
                        props["thunderbolt_artifact_id"] = "thunderbolt-forge-1.20.1"
                    requests = []

                    def download(command, check):
                        requests.append(command)
                        upstream = "ae2lt" if "AE2-Lightning-Tech-Reborn/AE2-Lightning-Tech-Reborn" in command else "thunderbolt"
                        version = props[f"{upstream}_version"]
                        prefix = "forge-1.20.1-v" if forge else "v"
                        self.assertEqual(command[3], prefix + version)
                        self.assertEqual(command[6], "--pattern")
                        name = f'{upstream}{"-forge-1.20.1" if forge else ""}-{version}.jar'
                        self.assertEqual(command[7], name)
                        self.assertTrue(check)
                        jar_fixture(Path(command[9]) / name, upstream, version, forge)

                    with patch.object(release.subprocess, "run", side_effect=download):
                        outputs = release.download_dependencies(root, props, "AE2-Lightning-Tech-Reborn")
                    self.assertEqual(len(requests), 2 if mod_id == "ae2ltpp" else 1)
                    for notation in outputs.values():
                        group, artifact, version = notation.split(":")
                        folder = root / "release-dependencies/maven" / group.replace(".", "/") / artifact / version
                        self.assertTrue((folder / f"{artifact}-{version}.jar").is_file())
                        pom = ET.parse(folder / f"{artifact}-{version}.pom")
                        self.assertEqual(pom.findtext("{*}artifactId"), artifact)
                        self.assertEqual(pom.findtext("{*}version"), version)

    def test_rejects_dependency_with_wrong_version_or_loader(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "dependency.jar"
            jar_fixture(jar, "thunderbolt", "2.0.0")
            with self.assertRaises(ValueError):
                release.validate_jar(jar, "thunderbolt", "2.0.1", "META-INF/neoforge.mods.toml")
            with self.assertRaises(KeyError):
                release.validate_jar(jar, "thunderbolt", "2.0.0", "META-INF/mods.toml")

    def test_packages_only_distributable_and_records_its_digest(self):
        for game in ("1.21.1", "1.20.1"):
            for mod_id in release.PROJECTS:
                with self.subTest(game=game, mod_id=mod_id), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    prefix = "forge-1.20.1-v" if game == "1.20.1" else "v"
                    result = release.metadata({"minecraft_version": game}, prefix + "2.0.1-beta", True)
                    jar = root / "build/libs/distributable.jar"
                    jar_fixture(jar, mod_id, result["version"], game == "1.20.1")
                    (jar.parent / "mod-slim.jar").write_bytes(b"excluded")
                    (jar.parent / "mod-sources.jar").write_bytes(b"excluded")
                    release.prepare_artifacts(root, {"mod_id": mod_id}, result)
                    checksum = (root / "release-artifacts/SHA256SUMS").read_text()
                    self.assertEqual(checksum, hashlib.sha256(jar.read_bytes()).hexdigest() + "  distributable.jar\n")
                    (jar.parent / "extra.jar").write_bytes(b"unexpected")
                    with self.assertRaises(ValueError):
                        release.prepare_artifacts(root, {"mod_id": mod_id}, result)


if __name__ == "__main__":
    unittest.main()
