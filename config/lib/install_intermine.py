#!/usr/bin/env python3

import argparse
import glob
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from subprocess import run
from urllib.parse import urlsplit

EXIT_SUCCESS = 0
EXIT_FAILURE = 1
EXIT_USER = 2

GRADLE_DISTRIBUTION = "https://services.gradle.org/distributions/gradle-8.14.7-bin.zip"
GRADLE_SHA256 = "903bfc4bc7622323124054c10f509246212a1902948bdaf26c379cca8735e856"


@dataclass
class IntermineBuilder:
    central_url: str
    clean: bool
    clojars_url: str
    ebi_url: str
    gradle_distribution_url: str
    offline: bool
    offline_url: str
    plugins_url: str
    gradle_distribution_sha256: str | None = None

    def __post_init__(self) -> None:
        python_lib_dir = os.path.dirname(os.path.realpath(__file__))
        self.checkout = os.path.join(python_lib_dir, "..", "..")
        self.psql_host = self.getenv("PSQL_HOST")
        self.psql_user = self.getenv("PSQL_USER")
        self.psql_pwd = self.getenv("PSQL_PWD")

    def getenv(self, name: str) -> str:
        value = os.getenv(name)
        if value is None:
            print(f"You must set the environment variable {name}")
            sys.exit(EXIT_USER)

        return value

    def build(self) -> None:
        self.create_gradle_properties()
        self.create_gradle_wrapper_properties()
        self.copy_test_properties()
        self.gradle_install_modules()

    def create_gradle_properties(self) -> None:
        repositories = {
            "central": self.central_url,
            "clojars": self.clojars_url,
            "ebi": self.ebi_url,
            "plugins": self.plugins_url,
        }
        replacement_dict = {"im_checkout": self.checkout}
        for name, url in repositories.items():
            replacement_dict[f"im_{name}_url"] = url
            replacement_dict[f"im_{name}_allow_insecure"] = str(
                urlsplit(url).scheme == "http"
            ).lower()

        self.create_properties("gradle.properties", replacement_dict)

    def create_gradle_wrapper_properties(self) -> None:
        checksum = self.gradle_distribution_sha256
        official_distribution = self.gradle_distribution_url == GRADLE_DISTRIBUTION
        offline_distribution = (
            self.offline
            and self.gradle_distribution_url == GRADLE_DISTRIBUTION.rsplit("/", 1)[-1]
        )
        if checksum is None and (official_distribution or offline_distribution):
            checksum = GRADLE_SHA256
        replacement_dict = {
            "im_gradle_distribution_url": self.gradle_distribution_url,
            "im_gradle_distribution_checksum": f"distributionSha256Sum={checksum}"
            if checksum
            else "",
        }

        self.create_properties("gradle-wrapper.properties", replacement_dict)

    def create_properties(
        self, filename: str, replacement_dict: dict[str, str]
    ) -> None:
        for properties_in in glob.glob(
            f"**/{filename}.in", root_dir=self.checkout, recursive=True
        ):
            path_in = os.path.join(self.checkout, properties_in)
            path_out = path_in[:-3]
            with open(path_out, "w") as f_out:
                f_out.write(
                    "# FILE AUTOMATICALLY GENERATED FROM "
                    f"{properties_in}. DO NOT EDIT!\n"
                )
                with open(path_in) as f_in:
                    for line in f_in:
                        for key, value in replacement_dict.items():
                            line = line.replace(f"@@{key}@@", value)
                        f_out.write(line)

            print(f"Written {path_out}")

    def copy_test_properties(self) -> None:
        self.copy_properties("ci.properties", "intermine-test.properties")
        self.copy_properties("testmodel.properties", "testmodel.properties")
        self.copy_properties("ci-bio.properties", "intermine-bio-test.properties")

    def copy_properties(self, source_name: str, dest_name: str) -> None:
        dot_intermine_dir = os.path.join(Path.home(), ".intermine")
        os.makedirs(dot_intermine_dir, exist_ok=True)

        source_file = os.path.join(self.checkout, "config", source_name)
        dest_file = os.path.join(dot_intermine_dir, dest_name)

        if os.path.exists(dest_file):
            print(f"Skipping {dest_file} as it already exists")
            return

        with open(dest_file, "w") as f_out:
            f_out.write(
                f"# FILE AUTOMATICALLY GENERATED FROM {source_file}. DO NOT EDIT!\n"
            )
            with open(source_file) as f_in:
                for line in f_in:
                    line = line.replace("PSQL_HOST", self.psql_host)
                    line = line.replace("PSQL_USER", self.psql_user)
                    line = line.replace("PSQL_PWD", self.psql_pwd)

                    f_out.write(line)
        print(f"Written {dest_file}")

    def gradle_install_modules(self, clean=False) -> None:
        modules = [
            "plugin",
            "intermine",
            "bio",
            "bio/sources",
            "bio/postprocess",
        ]

        for module in modules:
            self.gradle_install(module)

    def gradle_install(self, module: str) -> None:
        module_dir = os.path.join(self.checkout, module)
        os.chdir(module_dir)
        if self.clean:
            run(["./gradlew", "clean", "--stacktrace"], check=True)
        run(["./gradlew", "install", "--stacktrace"], check=True)


def sha256_digest(value: str) -> str:
    if not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise argparse.ArgumentTypeError(
            "SHA-256 must contain 64 hexadecimal characters"
        )
    return value.lower()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Install Intermine",
        epilog="Selecting an http:// Maven URL explicitly enables HTTP for that repository.",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Run gradlew --clean before install",
    )

    parser.add_argument(
        "--offline",
        action="store_true",
        help="Use offline Maven repositories",
    )

    parser.add_argument(
        "--offline_url",
        default="http://localhost:8081/repository/maven-public/",
        help="URL of offline Maven repository",
    )

    parser.add_argument(
        "--central_url",
        default="https://repo1.maven.org/maven2/",
        help="URL of Maven Central repository",
    )

    parser.add_argument(
        "--clojars_url",
        default="https://clojars.org/repo",
        help="URL of Clojars Maven repository",
    )

    parser.add_argument(
        "--ebi_url",
        default=("https://www.ebi.ac.uk/Tools/maven/repos/content/groups/ebi-repo/"),
        help="URL of EMBL-EBI Maven repository",
    )

    parser.add_argument(
        "--plugins_url",
        default="https://plugins.gradle.org/m2/",
        help="URL of Gradle plugins Maven repository",
    )

    parser.add_argument(
        "--gradle_distribution_url",
        default=GRADLE_DISTRIBUTION,
        help="URL of the Gradle distribution ZIP",
    )

    parser.add_argument(
        "--gradle_distribution_sha256",
        type=sha256_digest,
        help="SHA-256 of a custom Gradle distribution; the official distribution is verified automatically",
    )

    args = parser.parse_args()

    if args.offline:
        args.central_url = args.offline_url
        args.clojars_url = args.offline_url
        args.ebi_url = args.offline_url
        args.plugins_url = args.offline_url

        gradle_zip = args.gradle_distribution_url.rsplit("/", 1)[-1]
        args.gradle_distribution_url = gradle_zip

    builder = IntermineBuilder(**vars(args))
    builder.build()


if __name__ == "__main__":
    main()
