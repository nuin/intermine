import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from install_intermine import IntermineBuilder, main


class WrapperConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.checkout = Path(self.directory.name)
        template = (
            Path(__file__).resolve().parents[2]
            / "intermine/gradle/wrapper/gradle-wrapper.properties.in"
        )
        self.wrapper = self.checkout / "gradle/wrapper/gradle-wrapper.properties"
        self.wrapper.parent.mkdir(parents=True)
        self.wrapper.with_suffix(".properties.in").write_text(template.read_text())

    def configure(self, distribution, checksum=None, offline=False):
        with patch.dict(
            os.environ,
            {"PSQL_HOST": "localhost", "PSQL_USER": "test", "PSQL_PWD": "test"},
        ):
            builder = IntermineBuilder(
                central_url="https://repo.maven.apache.org/maven2/",
                clean=False,
                clojars_url="https://clojars.org/repo",
                ebi_url="https://www.ebi.ac.uk/Tools/maven/repos/content/groups/ebi-repo/",
                gradle_distribution_url=distribution,
                offline=offline,
                offline_url="http://localhost:8081/repository/maven-public/",
                plugins_url="https://plugins.gradle.org/m2/",
            )
        builder.checkout = str(self.checkout)
        builder.gradle_distribution_sha256 = checksum
        builder.create_gradle_wrapper_properties()
        return self.wrapper.read_text()

    def test_official_distribution_has_integrity_check(self):
        rendered = self.configure(
            "https://services.gradle.org/distributions/gradle-8.14.7-bin.zip"
        )
        self.assertIn(
            "distributionSha256Sum=903bfc4bc7622323124054c10f509246212a1902948bdaf26c379cca8735e856",
            rendered,
        )
        self.assertNotIn("@@", rendered)

    def test_custom_distribution_uses_supplied_checksum(self):
        checksum = "a" * 64
        rendered = self.configure("file:///opt/gradle/custom.zip", checksum)
        self.assertIn("distributionUrl=file:///opt/gradle/custom.zip", rendered)
        self.assertIn("distributionSha256Sum=" + checksum, rendered)

    def test_custom_distribution_does_not_inherit_official_checksum(self):
        rendered = self.configure("https://example.org/custom-gradle.zip")
        self.assertNotIn("distributionSha256Sum=", rendered)

    def test_offline_official_distribution_keeps_integrity_check(self):
        rendered = self.configure("gradle-8.14.7-bin.zip", offline=True)
        self.assertIn("distributionUrl=gradle-8.14.7-bin.zip", rendered)
        self.assertIn(
            "distributionSha256Sum=903bfc4bc7622323124054c10f509246212a1902948bdaf26c379cca8735e856",
            rendered,
        )

    def test_cli_rejects_invalid_checksum_before_building(self):
        result = subprocess.run(
            [
                sys.executable,
                str(Path(__file__).with_name("install_intermine.py")),
                "--gradle_distribution_sha256",
                "invalid",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(2, result.returncode)
        self.assertIn("64 hexadecimal characters", result.stderr)


class RepositoryConfigurationTest(unittest.TestCase):
    repositories = ("central", "clojars", "ebi", "plugins")

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.checkout = Path(self.directory.name)
        (self.checkout / "config/lib").mkdir(parents=True)
        repository = Path(__file__).resolve().parents[2]
        self.templates = list(repository.glob("**/gradle.properties.in"))
        for template in self.templates:
            target = self.checkout / template.relative_to(repository)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(template.read_text())

    def configure(self, *arguments):
        with (
            patch.dict(
                os.environ,
                {"PSQL_HOST": "localhost", "PSQL_USER": "test", "PSQL_PWD": "test"},
            ),
            patch(
                "install_intermine.__file__",
                str(self.checkout / "config/lib/install_intermine.py"),
            ),
            patch.object(sys, "argv", ["install_intermine", *arguments]),
            patch.object(IntermineBuilder, "copy_test_properties"),
            patch.object(IntermineBuilder, "gradle_install_modules"),
        ):
            main()
        properties_files = list(self.checkout.glob("**/gradle.properties"))
        self.assertEqual(len(self.templates), len(properties_files))
        return [
            dict(
                line.split("=", 1)
                for line in path.read_text().splitlines()
                if line.startswith("intermine.maven.")
            )
            for path in properties_files
        ]

    def test_default_https_repositories_do_not_allow_insecure_protocol(self):
        for properties in self.configure():
            for repository in self.repositories:
                with self.subTest(repository=repository):
                    self.assertTrue(
                        properties[f"intermine.maven.{repository}"].startswith("https://")
                    )
                    self.assertEqual(
                        "false",
                        properties.get(
                            f"intermine.maven.{repository}.allowInsecureProtocol"
                        ),
                    )

    def test_custom_http_repository_opt_in_is_scoped_to_selected_repository(self):
        for selected in self.repositories:
            url = "http://localhost:8081/repository/custom/"
            for properties in self.configure(f"--{selected}_url", url):
                self.assertEqual(url, properties[f"intermine.maven.{selected}"])
                for repository in self.repositories:
                    with self.subTest(selected=selected, repository=repository):
                        self.assertEqual(
                            "true" if repository == selected else "false",
                            properties.get(
                                f"intermine.maven.{repository}.allowInsecureProtocol"
                            ),
                        )

    def test_offline_http_mirror_allows_each_redirected_repository(self):
        for properties in self.configure("--offline"):
            for repository in self.repositories:
                with self.subTest(repository=repository):
                    self.assertEqual(
                        "http://localhost:8081/repository/maven-public/",
                        properties[f"intermine.maven.{repository}"],
                    )
                    self.assertEqual(
                        "true",
                        properties.get(
                            f"intermine.maven.{repository}.allowInsecureProtocol"
                        ),
                    )

    def test_offline_https_mirror_does_not_allow_insecure_protocol(self):
        for properties in self.configure(
            "--offline", "--offline_url", "https://mirror.example/maven/"
        ):
            for repository in self.repositories:
                with self.subTest(repository=repository):
                    self.assertEqual(
                        "https://mirror.example/maven/",
                        properties[f"intermine.maven.{repository}"],
                    )
                    self.assertEqual(
                        "false",
                        properties.get(
                            f"intermine.maven.{repository}.allowInsecureProtocol"
                        ),
                    )


if __name__ == "__main__":
    unittest.main()
