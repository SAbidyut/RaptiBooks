#!/usr/bin/env python3
"""
RaptiBooks offline APK builder.

Builds the Android app without the Android SDK and without Google's Maven repository,
for sandboxes/CI where dl.google.com is unreachable. Every input is an official binary:

  * android.jar   -> Robolectric "android-all" (AOSP framework classes + resources.arsc), Maven Central
  * aapt2, D8, manifest merger, apksig
                  -> Microsoft.Android.Sdk.Linux NuGet pack (ships Google's binaries)
  * AndroidX AARs -> Microsoft's Xamarin.AndroidX NuGet packages (Google's original AARs,
                     mapped via https://github.com/dotnet/android-libraries config.json)
  * Kotlin compiler + Compose compiler plugin + other JVM libs -> Maven Central (via Gradle)

The Gradle build stays the single source of truth: modules, namespaces and dependencies are
parsed from settings.gradle.kts, <module>/build.gradle.kts and gradle/libs.versions.toml.
With a normal Android SDK you should simply use `./gradlew assembleRelease` instead.

Usage:
  python3 tools/offline-build/rbuild.py fetch   # resolve + download everything (cached)
  python3 tools/offline-build/rbuild.py apk     # build signed APK -> build/offline/RaptiBooks.apk
  python3 tools/offline-build/rbuild.py test    # compile + run JUnit tests of pure-Kotlin modules
  python3 tools/offline-build/rbuild.py robotest  # Robolectric tests of the app (DB, pipeline, UI)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import tomllib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL_DIR = Path(__file__).resolve().parent
CACHE = Path(os.environ.get("RB_CACHE", Path.home() / ".cache" / "raptibooks-offline"))
OUT = ROOT / "build" / "offline"

MAVEN_REPOS = [
    "https://maven-central.storage-download.googleapis.com/maven2",
    "https://repo.maven.apache.org/maven2",
]
NUGET = "https://api.nuget.org/v3-flatcontainer"
MAPPING_URL = "https://raw.githubusercontent.com/dotnet/android-libraries/main/config.json"
ANDROID_ALL = {
    "35": "15-robolectric-12650502",
    "36": "16-robolectric-13921718",
}
DOTNET_PACK = ("microsoft.android.sdk.linux", "35.0.105")
GOOGLE_GROUP_PREFIXES = ("androidx.", "com.google.android.material")

ANDROID_NS = "http://schemas.android.com/apk/res/android"


def log(msg: str) -> None:
    print(f"[rbuild] {msg}", flush=True)


def die(msg: str) -> None:
    print(f"[rbuild] ERROR: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


# --------------------------------------------------------------------------------------
# Networking
# --------------------------------------------------------------------------------------

def http_get(url: str, dest: Path | None = None, retries: int = 5) -> bytes | Path:
    last: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "raptibooks-rbuild/1.0"})
            with urllib.request.urlopen(req, timeout=180) as resp:
                if dest is None:
                    return resp.read()
                dest.parent.mkdir(parents=True, exist_ok=True)
                tmp = dest.with_name(dest.name + ".part")
                with open(tmp, "wb") as fh:
                    shutil.copyfileobj(resp, fh, 1 << 20)
                tmp.replace(dest)
                return dest
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
            last = e
        except Exception as e:  # noqa: BLE001 - network errors of all kinds are retried
            last = e
        time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"GET {url} failed: {last}")


def is_zip(path: Path) -> bool:
    try:
        with zipfile.ZipFile(path) as zf:
            return len(zf.namelist()) > 0
    except (zipfile.BadZipFile, OSError):
        return False


def download_zip(url_candidates: list[str], dest: Path) -> Path:
    if dest.exists() and is_zip(dest):
        return dest
    errors = []
    for url in url_candidates:
        try:
            http_get(url, dest)
            if is_zip(dest):
                return dest
            errors.append(f"{url}: not a zip (rate limited?)")
            dest.unlink(missing_ok=True)
        except Exception as e:  # noqa: BLE001
            errors.append(f"{url}: {e}")
    raise RuntimeError("download failed:\n  " + "\n  ".join(errors))


def maven_urls(group: str, artifact: str, version: str, ext: str = "jar", classifier: str | None = None) -> list[str]:
    name = f"{artifact}-{version}" + (f"-{classifier}" if classifier else "") + f".{ext}"
    path = f"{group.replace('.', '/')}/{artifact}/{version}/{name}"
    return [f"{repo}/{path}" for repo in MAVEN_REPOS]


# --------------------------------------------------------------------------------------
# Project model (parsed from the Gradle build)
# --------------------------------------------------------------------------------------

@dataclass
class Dep:
    config: str                  # api / implementation / compileOnly / testImplementation ...
    kind: str                    # project | maven
    value: str                   # ":core:model" or "group:artifact:version[:classifier]"
    excludes: list[str] = field(default_factory=list)  # excluded groups


@dataclass
class Module:
    name: str
    path: Path
    kind: str                    # app | android | jvm
    compose: bool
    namespace: str | None
    deps: list[Dep]

    @property
    def src_dirs(self) -> list[Path]:
        return [d for d in (self.path / "src/main/kotlin", self.path / "src/main/java") if d.is_dir()]

    @property
    def test_dirs(self) -> list[Path]:
        return [d for d in (self.path / "src/test/kotlin", self.path / "src/test/java") if d.is_dir()]

    @property
    def res_dir(self) -> Path:
        return self.path / "src/main/res"

    @property
    def assets_dir(self) -> Path:
        return self.path / "src/main/assets"

    @property
    def manifest(self) -> Path:
        return self.path / "src/main/AndroidManifest.xml"

    @property
    def safe_name(self) -> str:
        return self.name.strip(":").replace(":", "-")


@dataclass
class Project:
    modules: dict[str, Module]
    catalog: dict
    app: dict

    def version(self, key: str) -> str:
        return str(self.catalog["versions"][key])


def load_catalog() -> dict:
    with open(ROOT / "gradle/libs.versions.toml", "rb") as fh:
        return tomllib.load(fh)


def catalog_coordinate(catalog: dict, accessor: str) -> str:
    """libs.androidx.core.ktx -> group:artifact:version"""
    wanted = accessor.removeprefix("libs.")
    for key, spec in catalog["libraries"].items():
        if key.replace("-", ".").replace("_", ".") != wanted:
            continue
        if isinstance(spec, str):
            return spec
        module = spec.get("module") or f"{spec['group']}:{spec['name']}"
        version = spec.get("version")
        if isinstance(version, dict):
            version = version.get("ref") and catalog["versions"][version["ref"]] or version.get("strictly")
        elif version is None and "version.ref" in spec:
            version = catalog["versions"][spec["version.ref"]]
        if version is None:
            raise ValueError(f"no version for {accessor}")
        return f"{module}:{version}"
    raise KeyError(f"catalog entry not found for {accessor}")


def interpolate(text: str, catalog: dict) -> str:
    def repl(m: re.Match) -> str:
        key = m.group(1)
        for k, v in catalog["versions"].items():
            if k.replace("-", ".").replace("_", ".") == key:
                return str(v)
        raise KeyError(key)
    return re.sub(r"\$\{libs\.versions\.([\w.]+)\.get\(\)\}", repl, text)


DEP_LINE = re.compile(r"^\s*(api|implementation|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\((.*)\)\s*(\{)?\s*$")


def parse_module(name: str, catalog: dict) -> Module:
    path = ROOT / name.strip(":").replace(":", "/")
    text = (path / "build.gradle.kts").read_text()
    if "libs.plugins.android.application" in text:
        kind = "app"
    elif "libs.plugins.android.library" in text:
        kind = "android"
    elif "libs.plugins.kotlin.jvm" in text:
        kind = "jvm"
    else:
        raise ValueError(f"unknown module type for {name}")
    compose = "libs.plugins.kotlin.compose" in text
    ns = re.search(r'namespace\s*=\s*"([^"]+)"', text)
    deps: list[Dep] = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        m = DEP_LINE.match(lines[i])
        if m:
            config, arg, block = m.group(1), m.group(2).strip(), m.group(3)
            excludes: list[str] = []
            if block:
                i += 1
                while i < len(lines) and lines[i].strip() != "}":
                    ex = re.search(r'exclude\(group\s*=\s*"([^"]+)"', lines[i])
                    if ex:
                        excludes.append(ex.group(1))
                    i += 1
            pm = re.fullmatch(r'project\("([^"]+)"\)', arg)
            if pm:
                deps.append(Dep(config, "project", pm.group(1)))
            elif arg.startswith("libs."):
                deps.append(Dep(config, "maven", catalog_coordinate(catalog, arg), excludes))
            elif arg.startswith('"'):
                deps.append(Dep(config, "maven", interpolate(arg.strip('"'), catalog), excludes))
            else:
                raise ValueError(f"cannot parse dependency in {name}: {lines[i]}")
        i += 1
    return Module(name, path, kind, compose, ns.group(1) if ns else None, deps)


def load_project() -> Project:
    catalog = load_catalog()
    settings = (ROOT / "settings.gradle.kts").read_text()
    names = re.findall(r'include\("([^"]+)"\)', settings)
    modules = {n: parse_module(n, catalog) for n in names}
    app_mod = next(m for m in modules.values() if m.kind == "app")
    text = (app_mod.path / "build.gradle.kts").read_text()

    def num(field_name: str) -> int:
        m = re.search(field_name + r"\s*=\s*(\d+|libs\.versions\.(\w+)\.get\(\)\.toInt\(\))", text)
        if not m:
            raise ValueError(field_name)
        return int(m.group(1)) if m.group(1).isdigit() else int(catalog["versions"][m.group(2)])

    abis = re.search(r"abiFilters\s*\+=\s*listOf\(([^)]*)\)", text)
    store = re.search(r'storeFile\s*=\s*rootProject\.file\("([^"]+)"\)', text)
    app = {
        "module": app_mod.name,
        "applicationId": re.search(r'applicationId\s*=\s*"([^"]+)"', text).group(1),
        "versionCode": num("versionCode"),
        "versionName": re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1),
        "minSdk": num("minSdk"),
        "targetSdk": num("targetSdk"),
        "compileSdk": num("compileSdk"),
        "abis": re.findall(r'"([^"]+)"', abis.group(1)) if abis else ["arm64-v8a"],
        "keystore": str(ROOT / store.group(1)) if store else None,
        "storePassword": re.search(r'storePassword\s*=\s*"([^"]+)"', text).group(1) if store else None,
        "keyAlias": re.search(r'keyAlias\s*=\s*"([^"]+)"', text).group(1) if store else None,
        "keyPassword": re.search(r'keyPassword\s*=\s*"([^"]+)"', text).group(1) if store else None,
    }
    return Project(modules, catalog, app)


def topo_order(project: Project) -> list[Module]:
    seen: dict[str, bool] = {}
    order: list[Module] = []

    def visit(name: str) -> None:
        if seen.get(name):
            return
        seen[name] = True
        for d in project.modules[name].deps:
            if d.kind == "project" and not d.config.startswith("test"):
                visit(d.value)
        order.append(project.modules[name])

    for n in project.modules:
        visit(n)
    return order


# --------------------------------------------------------------------------------------
# Dependency resolution
# --------------------------------------------------------------------------------------

def is_google(group: str) -> bool:
    return group.startswith(GOOGLE_GROUP_PREFIXES) or group == "com.google.android.material"


def ga(coord: str) -> str:
    parts = coord.split(":")
    return f"{parts[0]}:{parts[1]}"


def version_key(v: str) -> tuple:
    out = []
    for part in re.split(r"[.\-]", v):
        out.append((0, int(part)) if part.isdigit() else (-1, part))
    return tuple(out)


class NugetResolver:
    """Resolves AndroidX Maven coordinates through Microsoft's Xamarin.AndroidX packages."""

    def __init__(self, pins: dict[str, str]):
        self.pins = pins
        mapping_file = CACHE / "nuget" / "config.json"
        if not mapping_file.exists():
            http_get(MAPPING_URL, mapping_file)
        cfg = json.loads(mapping_file.read_text())
        cfg = cfg[0] if isinstance(cfg, list) else cfg
        self.by_ga: dict[str, str] = {}
        self.by_nuget: dict[str, str] = {}
        for a in cfg["artifacts"]:
            if not a.get("nugetId"):
                continue
            key = f"{a['groupId']}:{a['artifactId']}"
            self.by_ga[key] = a["nugetId"]
            self.by_nuget[a["nugetId"].lower()] = key
        self._versions: dict[str, list[str]] = {}
        self._nuspec: dict[tuple[str, str], tuple[str | None, list[tuple[str, str]]]] = {}

    def versions(self, nuget_id: str) -> list[str]:
        nid = nuget_id.lower()
        if nid not in self._versions:
            f = CACHE / "nuget" / "index" / f"{nid}.json"
            if not f.exists() or time.time() - f.stat().st_mtime > 7 * 86400:
                http_get(f"{NUGET}/{nid}/index.json", f)
            self._versions[nid] = json.loads(f.read_text())["versions"]
        return self._versions[nid]

    def nuspec(self, nuget_id: str, version: str) -> tuple[str | None, list[tuple[str, str]]]:
        """returns (maven version from tags, [(dep nuget id, min version)])"""
        nid = nuget_id.lower()
        key = (nid, version)
        if key in self._nuspec:
            return self._nuspec[key]
        f = CACHE / "nuget" / "nuspec" / f"{nid}.{version}.nuspec"
        if not f.exists():
            http_get(f"{NUGET}/{nid}/{version}/{nid}.nuspec", f)
        root = ET.fromstring(f.read_bytes())
        ns = {"n": root.tag.split("}")[0].strip("{")} if root.tag.startswith("{") else {}
        md = root.find("n:metadata", ns) if ns else root.find("metadata")
        tags = (md.findtext("n:tags", default="", namespaces=ns) if ns else md.findtext("tags", default="")) or ""
        mv = None
        m = re.search(r"artifact_versioned=([^\s]+)", tags)
        if m:
            mv = m.group(1).split(":")[-1]
        deps_el = md.find("n:dependencies", ns) if ns else md.find("dependencies")
        best_group = None
        best_rank: tuple = ()
        groups = deps_el.findall("n:group", ns) if (deps_el is not None and ns) else (deps_el.findall("group") if deps_el is not None else [])
        for g in groups:
            tf = g.get("targetFramework", "")
            nm = re.match(r"net(\d+)\.(\d+)-android(\d+)?", tf)
            rank = (1, int(nm.group(1)), int(nm.group(2))) if nm else (0, 0, 0)
            if best_group is None or rank > best_rank:
                best_group, best_rank = g, rank
        deps: list[tuple[str, str]] = []
        if best_group is not None:
            for d in (best_group.findall("n:dependency", ns) if ns else best_group.findall("dependency")):
                rng = d.get("version", "")
                deps.append((d.get("id"), rng.strip("[]() ").split(",")[0].strip()))
        self._nuspec[key] = (mv, deps)
        return self._nuspec[key]

    def nuget_version_for(self, nuget_id: str, maven_version: str) -> str | None:
        cands = []
        for nv in self.versions(nuget_id):
            if nv == maven_version or (nv.startswith(maven_version + ".") and nv[len(maven_version) + 1:].isdigit()):
                cands.append(nv)
        cands.sort(key=version_key)
        for nv in cands:
            mv, _ = self.nuspec(nuget_id, nv)
            if mv == maven_version:
                return nv
        return None

    def resolve(self, roots: list[str]) -> tuple[dict, dict[str, str]]:
        """roots: list of g:a:v. Returns (nodes, external) where nodes[g:a] = {...} and
        external[g:a] = maven version for non-AndroidX deps (resolved later through Gradle)."""
        selected: dict[str, str] = {}
        requested_by_root: dict[str, str] = {}
        external: dict[str, str] = {}
        queue: list[tuple[str, str]] = []
        for c in roots:
            g, a, v = c.split(":")[:3]
            requested_by_root[f"{g}:{a}"] = v
            queue.append((f"{g}:{a}", v))
        while queue:
            key, want = queue.pop()
            group = key.split(":")[0]
            pinned = self.pins.get(key) or self.pins.get(group)
            version = want
            if key in requested_by_root:
                version = requested_by_root[key]
            elif pinned and self._has(key, pinned):
                version = pinned
            cur = selected.get(key)
            if cur and version_key(cur) >= version_key(version):
                continue
            selected[key] = version
            nid = self.by_ga.get(key)
            if not nid:
                die(f"no NuGet mapping for {key}")
            nv = self.nuget_version_for(nid, version)
            if nv is None:
                die(f"NuGet package {nid} has no build of Maven version {version}")
            _, deps = self.nuspec(nid, nv)
            for dep_id, dep_ver in deps:
                dep_key = self.by_nuget.get(dep_id.lower())
                if not dep_key:
                    continue  # .NET-only helper packages
                dep_mv, _ = self.nuspec(dep_id, dep_ver)
                dep_mv = dep_mv or dep_ver
                if is_google(dep_key.split(":")[0]):
                    queue.append((dep_key, dep_mv))
                else:
                    prev = external.get(dep_key)
                    if not prev or version_key(dep_mv) > version_key(prev):
                        external[dep_key] = dep_mv
        nodes: dict[str, dict] = {}
        for key, version in selected.items():
            nid = self.by_ga[key]
            nv = self.nuget_version_for(nid, version)
            _, deps = self.nuspec(nid, nv)
            dep_keys = [self.by_nuget[d.lower()] for d, _ in deps if d.lower() in self.by_nuget]
            nodes[key] = {"version": version, "nuget": [nid, nv], "deps": dep_keys}
        return nodes, external

    def _has(self, key: str, maven_version: str) -> bool:
        nid = self.by_ga.get(key)
        return bool(nid) and self.nuget_version_for(nid, maven_version) is not None

    def fetch_files(self, key: str, node: dict) -> list[str]:
        nid, nv = node["nuget"]
        pkg = CACHE / "nuget" / "pkg" / f"{nid.lower()}.{nv}.nupkg"
        download_zip([f"{NUGET}/{nid.lower()}/{nv}/{nid.lower()}.{nv}.nupkg"], pkg)
        out_dir = CACHE / "libs" / key.replace(":", "/") / node["version"]
        files = []
        with zipfile.ZipFile(pkg) as zf:
            for name in zf.namelist():
                if re.fullmatch(r"(aar|jar)/[^/]+\.(aar|jar)", name):
                    dest = out_dir / Path(name).name
                    if not dest.exists():
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        with zf.open(name) as src, open(dest, "wb") as dst:
                            shutil.copyfileobj(src, dst)
                    files.append(str(dest))
        return files


GRADLE_RESOLVE_TEMPLATE = """
import groovy.json.JsonOutput

repositories {
    REPOS
}

val platformType = Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java)

fun resolvable(name: String) = configurations.create(name) {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
        attribute(platformType, "jvm")
    }
    EXCLUDES
}

CONFIGS

dependencies {
    DEPS
}

tasks.register("dump") {
    doLast {
        val result = mutableMapOf<String, Any>()
        for (name in listOf(CONFIG_NAMES)) {
            val conf = configurations.getByName(name)
            val comps = mutableMapOf<String, MutableMap<String, Any>>()
            conf.incoming.resolutionResult.allComponents.forEach { c ->
                val id = c.moduleVersion ?: return@forEach
                val key = id.group + ":" + id.name
                comps[key] = mutableMapOf(
                    "version" to id.version,
                    "deps" to c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .mapNotNull { it.selected.moduleVersion?.let { mv -> mv.group + ":" + mv.name } },
                    "files" to mutableListOf<String>()
                )
            }
            conf.incoming.artifacts.artifacts.forEach { a ->
                val cid = a.id.componentIdentifier
                if (cid is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                    @Suppress("UNCHECKED_CAST")
                    (comps[cid.group + ":" + cid.module]?.get("files") as MutableList<String>?)?.add(a.file.absolutePath)
                }
            }
            result[name] = comps
        }
        file("resolved.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(result)))
    }
}
"""


def gradle_executable() -> list[str]:
    env = os.environ.get("GRADLE")
    if env:
        return [env]
    found = shutil.which("gradle")
    if found:
        return [found]
    return [str(ROOT / "gradlew")]


def gradle_resolve(configs: dict[str, list[tuple[str, list[str]]]], excluded_groups: set[str]) -> dict:
    work = CACHE / "gradle-resolve"
    work.mkdir(parents=True, exist_ok=True)
    (work / "settings.gradle.kts").write_text('rootProject.name = "rb-resolve"\n')
    repos = "\n    ".join(f'maven("{r}")' for r in MAVEN_REPOS)
    excludes = "\n    ".join(f'exclude(mapOf("group" to "{g}"))' for g in sorted(excluded_groups))
    conf_decl = "\n".join(f'val {n} = resolvable("{n}")' for n in configs)
    dep_lines = []
    for n, coords in configs.items():
        for coord, exc in coords:
            if exc:
                inner = " ".join(f'exclude(group = "{g}")' for g in exc)
                dep_lines.append(f'add("{n}", "{coord}") {{ (this as ModuleDependency).apply {{ {inner} }} }}')
            else:
                dep_lines.append(f'add("{n}", "{coord}")')
    script = (GRADLE_RESOLVE_TEMPLATE
              .replace("REPOS", repos)
              .replace("EXCLUDES", excludes)
              .replace("CONFIGS", conf_decl)
              .replace("CONFIG_NAMES", ", ".join(f'"{n}"' for n in configs))
              .replace("DEPS", "\n    ".join(dep_lines)))
    (work / "build.gradle.kts").write_text(script)
    cmd = gradle_executable() + ["-q", "--no-daemon", "-p", str(work), "dump"]
    log("resolving Maven Central dependencies with Gradle ...")
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(res.stdout[-4000:], res.stderr[-6000:])
        die("gradle resolution failed")
    return json.loads((work / "resolved.json").read_text())


def collect_external(project: Project, test: bool) -> list[tuple[str, list[str]]]:
    seen: dict[str, tuple[str, list[str]]] = {}
    for m in project.modules.values():
        for d in m.deps:
            if d.kind != "maven":
                continue
            if d.config.startswith("test") != test:
                continue
            seen[d.value] = (d.value, d.excludes)
    return list(seen.values())


def cmd_fetch(project: Project) -> dict:
    kotlin = project.version("kotlin")
    main_deps = collect_external(project, test=False)
    test_deps = collect_external(project, test=True)

    google_roots = [c for c, _ in main_deps if is_google(c.split(":")[0])]
    maven_roots = [(c, e) for c, e in main_deps if not is_google(c.split(":")[0])]

    # Version alignment ("BOM"): Compose artifacts follow the compose version, other groups
    # follow the version of the directly declared artifact of that group.
    pins: dict[str, str] = {}
    compose = project.catalog["versions"].get("compose")
    if compose:
        for g in ("androidx.compose.ui", "androidx.compose.foundation", "androidx.compose.runtime",
                  "androidx.compose.animation", "androidx.compose.material"):
            pins[g] = str(compose)
    for c in google_roots:
        g, a, v = c.split(":")[:3]
        pins.setdefault(g, v)
        pins[f"{g}:{a}"] = v
    log(f"resolving {len(google_roots)} AndroidX roots through NuGet ...")
    nuget = NugetResolver(pins)
    nodes, external = nuget.resolve(google_roots)
    log(f"  {len(nodes)} AndroidX artifacts, {len(external)} external deps")

    for key, v in external.items():
        if key.startswith("org.jetbrains.kotlin:"):
            v = kotlin
        maven_roots.append((f"{key}:{v}", []))
    maven_roots.append((f"org.jetbrains.kotlin:kotlin-stdlib:{kotlin}", []))

    # AndroidX artifacts never come from Maven Central (the test-only androidx.test classes that
    # Robolectric needs are stubbed in tools/robolectric-stubs).
    excluded = {k.split(":")[0] for k in nodes} | {"androidx.test", "androidx.test.espresso", "androidx.test.ext"}
    configs = {
        "main": maven_roots,
        "kotlinc": [(f"org.jetbrains.kotlin:kotlin-compiler-embeddable:{kotlin}", [])],
        "composeplugin": [(f"org.jetbrains.kotlin:kotlin-compose-compiler-plugin-embeddable:{kotlin}", [])],
        "test": test_deps + [(f"org.jetbrains.kotlin:kotlin-stdlib:{kotlin}", [])],
    }
    gres = gradle_resolve(configs, excluded)

    libs: dict[str, dict] = {}
    for key, node in nodes.items():
        files = nuget.fetch_files(key, node)
        libs[key] = {"version": node["version"], "deps": node["deps"], "files": files, "source": "nuget"}
    for key, comp in gres["main"].items():
        if key in libs:
            continue
        libs[key] = {"version": comp["version"], "deps": comp["deps"], "files": comp["files"], "source": "maven"}

    # Kotlin Multiplatform variant selection: NuGet packages reference both the "-jvm" and the
    # "-android" variant of a KMP library; Gradle picks "-android" for Android consumers.
    for key in [k for k in libs if k.endswith("-jvm") and k[:-4] + "-android" in libs]:
        del libs[key]
    for info in libs.values():
        info["deps"] = [d for d in info["deps"] if d in libs or d not in nodes]

    resolved = {
        "libs": libs,
        "kotlinc": sorted({f for c in gres["kotlinc"].values() for f in c["files"]}),
        "composePlugin": sorted({f for c in gres["composeplugin"].values() for f in c["files"]
                                 if "compose-compiler-plugin" in f}),
        "test": sorted({f for c in gres["test"].values() for f in c["files"]}),
        "tools": fetch_tools(project),
    }
    OUT.mkdir(parents=True, exist_ok=True)
    (CACHE / "resolved.json").write_text(json.dumps(resolved, indent=1))
    log(f"resolved {len(libs)} libraries -> {CACHE / 'resolved.json'}")
    return resolved


def fetch_tools(project: Project) -> dict:
    tools_dir = CACHE / "tools"
    tools_dir.mkdir(parents=True, exist_ok=True)
    api = str(project.app["compileSdk"])
    if api not in ANDROID_ALL:
        die(f"no android-all mapping for compileSdk {api}")
    aa_ver = ANDROID_ALL[api]
    android_jar = download_zip(maven_urls("org.robolectric", "android-all", aa_ver),
                               tools_dir / f"android-all-{aa_ver}.jar")
    pid, pver = DOTNET_PACK
    pack = download_zip([f"{NUGET}/{pid}/{pver}/{pid}.{pver}.nupkg"], tools_dir / f"{pid}.{pver}.nupkg")
    sdk_dir = tools_dir / f"dotnet-android-{pver}"
    wanted = {"tools/Linux/aapt2": "aapt2", "tools/r8.jar": "r8.jar",
              "tools/manifestmerger.jar": "manifestmerger.jar", "tools/bundletool.jar": "bundletool.jar"}
    with zipfile.ZipFile(pack) as zf:
        for src, dst in wanted.items():
            target = sdk_dir / dst
            if not target.exists():
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(src) as s, open(target, "wb") as d:
                    shutil.copyfileobj(s, d)
    (sdk_dir / "aapt2").chmod(0o755)
    return {
        "androidJar": str(android_jar),
        "aapt2": str(sdk_dir / "aapt2"),
        "r8": str(sdk_dir / "r8.jar"),
        "manifestMerger": str(sdk_dir / "manifestmerger.jar"),
        "bundletool": str(sdk_dir / "bundletool.jar"),
    }


def load_resolved(project: Project, refresh: bool = False) -> dict:
    f = CACHE / "resolved.json"
    stamp_src = [ROOT / "gradle/libs.versions.toml", ROOT / "settings.gradle.kts"] + \
        [m.path / "build.gradle.kts" for m in project.modules.values()]
    if refresh or not f.exists() or any(p.stat().st_mtime > f.stat().st_mtime for p in stamp_src):
        return cmd_fetch(project)
    return json.loads(f.read_text())


# --------------------------------------------------------------------------------------
# Library extraction (AAR)
# --------------------------------------------------------------------------------------

@dataclass
class Lib:
    key: str
    version: str
    jars: list[Path]
    res_dir: Path | None = None
    manifest: Path | None = None
    assets_dir: Path | None = None
    jni_dir: Path | None = None
    package: str | None = None
    r_txt: Path | None = None


def explode(key: str, info: dict) -> Lib:
    lib = Lib(key, info["version"], [])
    for f in info["files"]:
        p = Path(f)
        if p.suffix == ".jar":
            lib.jars.append(p)
            continue
        if p.suffix != ".aar":
            continue
        dest = CACHE / "exploded" / key.replace(":", "/") / info["version"] / p.stem
        marker = dest / ".done"
        if not marker.exists():
            shutil.rmtree(dest, ignore_errors=True)
            dest.mkdir(parents=True)
            with zipfile.ZipFile(p) as zf:
                zf.extractall(dest)
            marker.touch()
        if (dest / "classes.jar").exists():
            lib.jars.append(dest / "classes.jar")
        lib.jars.extend(sorted((dest / "libs").glob("*.jar")) if (dest / "libs").is_dir() else [])
        if (dest / "res").is_dir() and any((dest / "res").rglob("*.*")):
            lib.res_dir = dest / "res"
        if (dest / "AndroidManifest.xml").exists():
            lib.manifest = dest / "AndroidManifest.xml"
            pkg = ET.parse(lib.manifest).getroot().get("package")
            lib.package = pkg
        if (dest / "assets").is_dir():
            lib.assets_dir = dest / "assets"
        if (dest / "jni").is_dir():
            lib.jni_dir = dest / "jni"
        if (dest / "R.txt").exists():
            lib.r_txt = dest / "R.txt"
    return lib


def closure(keys: list[str], libs: dict[str, dict]) -> list[str]:
    out: list[str] = []
    seen: set[str] = set()
    stack = list(keys)
    while stack:
        k = stack.pop()
        if k in seen or k not in libs:
            continue
        seen.add(k)
        out.append(k)
        stack.extend(libs[k]["deps"])
    return out


# --------------------------------------------------------------------------------------
# Build steps
# --------------------------------------------------------------------------------------

def run(cmd: list[str], what: str, cwd: Path | None = None, env: dict | None = None) -> subprocess.CompletedProcess:
    res = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd, env=env)
    out = (res.stdout + res.stderr).replace(os.environ.get("JAVA_TOOL_OPTIONS", "@@"), "")
    out = "\n".join(line for line in out.splitlines() if not line.startswith("Picked up JAVA_TOOL_OPTIONS"))
    if res.returncode != 0:
        print(out[-20000:])
        die(f"{what} failed (exit {res.returncode})")
    res.stdout = out
    return res


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin/java").exists():
        return str(Path(home) / "bin/java")
    return shutil.which("java") or die("java not found")


def java_home() -> str:
    j = Path(java()).resolve()
    return str(j.parent.parent)


def file_digest(paths: list[Path]) -> str:
    h = hashlib.sha256()
    for p in sorted(paths):
        if p.is_dir():
            for f in sorted(p.rglob("*")):
                if f.is_file():
                    h.update(str(f.relative_to(p)).encode())
                    h.update(f.read_bytes())
        elif p.exists():
            h.update(str(p).encode())
            h.update(p.read_bytes())
    return h.hexdigest()[:16]


def kotlinc(resolved: dict, name: str, sources: list[Path], classpath: list[Path], out_dir: Path,
            compose: bool, extra_sources: list[Path] | None = None) -> None:
    files = [str(f) for d in sources for f in d.rglob("*.kt")]
    java_files = [str(f) for d in sources for f in d.rglob("*.java")]
    if extra_sources:
        java_files += [str(f) for f in extra_sources]
    if not files and not java_files:
        out_dir.mkdir(parents=True, exist_ok=True)
        return
    digest = file_digest(sources + classpath + [TOOL_DIR / "rbuild.py"])
    stamp = out_dir / ".stamp"
    if stamp.exists() and stamp.read_text() == digest:
        log(f"  {name}: up to date")
        return
    shutil.rmtree(out_dir, ignore_errors=True)
    out_dir.mkdir(parents=True)
    args = [
        "-d", str(out_dir),
        "-module-name", name,
        "-jvm-target", "17",
        "-Xjdk-release=17",
        "-no-stdlib", "-no-reflect",
        "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions",
        "-opt-in=kotlin.RequiresOptIn",
        "-classpath", os.pathsep.join(str(c) for c in classpath),
    ]
    if compose:
        args += [f"-Xplugin={p}" for p in resolved["composePlugin"]]
    args += files + java_files
    argfile = out_dir.parent / f"{name}.kotlinc.args"
    argfile.write_text("\n".join(json.dumps(a) if " " in a else a for a in args))
    cmd = [java(), "-Xmx3g", "-Xss8m", "-cp", os.pathsep.join(resolved["kotlinc"]),
           "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", f"@{argfile}"]
    t0 = time.time()
    res = subprocess.run(cmd, capture_output=True, text=True)
    output = "\n".join(line for line in (res.stdout + res.stderr).splitlines()
                       if not line.startswith("Picked up JAVA_TOOL_OPTIONS"))
    warnings = [line for line in output.splitlines() if line.startswith("w: ")]
    if res.returncode != 0:
        print(output[-30000:])
        die(f"kotlinc {name} failed")
    if java_files:
        # Kotlin only reads Java sources; compile them with javac against the Kotlin output.
        jcp = os.pathsep.join([str(out_dir)] + [str(c) for c in classpath])
        run(["javac", "--release", "17", "-nowarn", "-d", str(out_dir), "-cp", jcp] + java_files, f"javac {name}")
    log(f"  {name}: compiled {len(files)} Kotlin files in {time.time() - t0:.0f}s"
        + (f" ({len(warnings)} warnings)" if warnings else ""))
    if os.environ.get("RB_WARNINGS"):
        print("\n".join(warnings))
    stamp.write_text(digest)


def module_classpath(project: Project, module: Module, libs: dict[str, dict], exploded: dict[str, Lib],
                     outputs: dict[str, Path], test: bool = False) -> tuple[list[Path], list[str]]:
    """Gradle-like compile classpath: own deps + api deps of project deps (transitively)."""
    project_deps: list[str] = []
    external: list[str] = []

    def add_external(dep: Dep) -> None:
        external.append(ga(dep.value))

    def walk_api(name: str) -> None:
        if name in project_deps:
            return
        project_deps.append(name)
        for d in project.modules[name].deps:
            if d.config == "api":
                if d.kind == "project":
                    walk_api(d.value)
                else:
                    add_external(d)

    for d in module.deps:
        if d.config.startswith("test") and not test:
            continue
        if d.config == "runtimeOnly" or d.config == "testRuntimeOnly":
            continue
        if d.kind == "project":
            walk_api(d.value)
        else:
            add_external(d)
    ext_keys = closure(external, libs)
    cp: list[Path] = [outputs[p] for p in project_deps if p in outputs]
    for k in ext_keys:
        if k in exploded:
            cp.extend(exploded[k].jars)
    return cp, ext_keys


def cmd_apk(project: Project, refresh: bool) -> Path:
    resolved = load_resolved(project, refresh)
    tools = resolved["tools"]
    android_jar = Path(tools["androidJar"])
    aapt2 = tools["aapt2"]
    libs_info: dict[str, dict] = resolved["libs"]
    app = project.app
    build_dir = OUT
    build_dir.mkdir(parents=True, exist_ok=True)

    order = topo_order(project)
    android_modules = [m for m in order if m.kind in ("android", "app")]

    # Runtime closure of the whole app = all main deps of all modules.
    all_main = []
    for m in order:
        for d in m.deps:
            if d.kind == "maven" and not d.config.startswith("test") and d.config != "compileOnly":
                all_main.append(ga(d.value))
    runtime_keys = closure(all_main, libs_info)
    exploded = {k: explode(k, libs_info[k]) for k in runtime_keys}
    log(f"{len(runtime_keys)} runtime libraries")

    # ---- 1. resources: compile -------------------------------------------------------
    res_flat_dir = build_dir / "res-flat"
    res_flat_dir.mkdir(exist_ok=True)
    lib_res: list[Path] = []
    # Overlay order: dependencies first so that dependants override them.
    lib_order = list(reversed(runtime_keys))
    for k in lib_order:
        lib = exploded[k]
        if lib.res_dir is None:
            continue
        out = CACHE / "res-flat" / (k.replace(":", "_") + "-" + lib.version + ".zip")
        if not out.exists():
            out.parent.mkdir(parents=True, exist_ok=True)
            run([aapt2, "compile", "--dir", str(lib.res_dir), "-o", str(out)], f"aapt2 compile {k}")
        lib_res.append(out)
    module_res: list[Path] = []
    app_res: Path | None = None
    for m in android_modules:
        if not m.res_dir.is_dir():
            continue
        out = res_flat_dir / f"{m.safe_name}.zip"
        digest = file_digest([m.res_dir])
        stamp = res_flat_dir / f"{m.safe_name}.stamp"
        if not (out.exists() and stamp.exists() and stamp.read_text() == digest):
            run([aapt2, "compile", "--dir", str(m.res_dir), "-o", str(out)], f"aapt2 compile {m.name}")
            stamp.write_text(digest)
        if m.kind == "app":
            app_res = out
        else:
            module_res.append(out)

    # ---- 2. manifest merge -------------------------------------------------------------
    app_mod = project.modules[app["module"]]
    merge_dir = build_dir / "manifests"
    shutil.rmtree(merge_dir, ignore_errors=True)
    merge_dir.mkdir()

    def with_package(src: Path, pkg: str, dest: Path, library: bool = False) -> Path:
        text = src.read_text()
        if "package=" not in text:
            text = re.sub(r"<manifest\b", f'<manifest package="{pkg}"', text, count=1)
        if library and "<uses-sdk" not in text:
            # AGP injects this; without it the merger assumes targetSdk 1 and adds
            # implied legacy permissions (READ_PHONE_STATE, storage).
            uses_sdk = (f'<uses-sdk android:minSdkVersion="{app["minSdk"]}" '
                        f'android:targetSdkVersion="{app["targetSdk"]}" />')
            if text.rstrip().endswith("/>") and "</manifest>" not in text:
                text = text.rstrip()[:-2] + ">\n    " + uses_sdk + "\n</manifest>\n"
            else:
                text = text.replace("</manifest>", "    " + uses_sdk + "\n</manifest>")
        dest.write_text(text)
        return dest

    main_manifest = with_package(app_mod.manifest, app_mod.namespace, merge_dir / "main.xml")
    lib_manifests = []
    for m in android_modules:
        if m.kind == "android" and m.manifest.exists():
            lib_manifests.append(with_package(m.manifest, m.namespace, merge_dir / f"{m.safe_name}.xml", library=True))
    for k in runtime_keys:
        if exploded[k].manifest:
            lib_manifests.append(exploded[k].manifest)
    merged = merge_dir / "AndroidManifest.xml"
    run([java(), "-cp", tools["manifestMerger"], "com.android.manifmerger.Merger",
         "--main", str(main_manifest),
         "--libs", os.pathsep.join(str(p) for p in lib_manifests),
         "--remove-tools-declarations",
         "--property", f"PACKAGE={app['applicationId']}",
         "--property", f"MIN_SDK_VERSION={app['minSdk']}",
         "--property", f"TARGET_SDK_VERSION={app['targetSdk']}",
         "--property", f"VERSION_CODE={app['versionCode']}",
         "--property", f"VERSION_NAME={app['versionName']}",
         "--out", str(merged)], "manifest merge")
    log("manifest merged")

    # ---- 3. link ---------------------------------------------------------------------------
    gen_dir = build_dir / "r-gen"
    shutil.rmtree(gen_dir, ignore_errors=True)
    gen_dir.mkdir()
    res_apk = build_dir / "resources.ap_"
    packages = []
    for m in android_modules:
        if m.namespace and m.namespace != app["applicationId"]:
            packages.append(m.namespace)
    for k in runtime_keys:
        lib = exploded[k]
        if lib.package and (lib.res_dir or lib.r_txt) and lib.package not in packages:
            packages.append(lib.package)
    link = [aapt2, "link", "-o", str(res_apk), "-I", str(android_jar),
            "--manifest", str(merged),
            "--min-sdk-version", str(app["minSdk"]),
            "--target-sdk-version", str(app["targetSdk"]),
            "--version-code", str(app["versionCode"]),
            "--version-name", app["versionName"],
            "--java", str(gen_dir),
            "--auto-add-overlay",
            "--no-version-vectors",
            "--extra-packages", ":".join(packages)]
    if app_res:
        link.append(str(app_res))
    for r in lib_res + module_res:
        link += ["-R", str(r)]
    run(link, "aapt2 link")
    log(f"resources linked ({len(packages)} R packages)")

    r_classes = build_dir / "r-classes"
    shutil.rmtree(r_classes, ignore_errors=True)
    r_classes.mkdir()
    r_sources = [str(p) for p in gen_dir.rglob("R.java")]
    run(["javac", "--release", "17", "-nowarn", "-encoding", "UTF-8", "-d", str(r_classes)] + r_sources, "javac R")
    r_jar = build_dir / "R.jar"
    make_jar(r_classes, r_jar)

    # ---- 4. Kotlin -----------------------------------------------------------------------
    outputs: dict[str, Path] = {}
    class_dirs: list[Path] = []
    for m in order:
        cp, _ = module_classpath(project, m, libs_info, exploded, outputs)
        cp += [Path(p) for p in stdlib_jars(libs_info)]
        if m.kind != "jvm":
            cp += [android_jar, r_jar]
        out_dir = build_dir / "classes" / m.safe_name
        kotlinc(resolved, m.safe_name, m.src_dirs, cp, out_dir, m.compose)
        outputs[m.name] = out_dir
        class_dirs.append(out_dir)

    # ---- 5. dex ------------------------------------------------------------------------------
    dex_dir = build_dir / "dex"
    shutil.rmtree(dex_dir, ignore_errors=True)
    dex_dir.mkdir()
    program_inputs: list[Path] = []
    for d in class_dirs:
        jar = build_dir / "classes" / f"{d.name}.jar"
        make_jar(d, jar)
        program_inputs.append(jar)
    program_inputs.append(r_jar)
    lib_jars: list[Path] = []
    for k in runtime_keys:
        lib_jars.extend(exploded[k].jars)
    d8 = [java(), "-Xmx6g", "-cp", tools["r8"], "com.android.tools.r8.D8",
          "--release", "--min-api", str(app["minSdk"]),
          "--lib", str(android_jar), "--lib", java_home(),
          "--output", str(dex_dir)]
    d8 += [str(p) for p in program_inputs + lib_jars]
    t0 = time.time()
    run(d8, "d8")
    log(f"dexed in {time.time() - t0:.0f}s -> {len(list(dex_dir.glob('*.dex')))} dex files")

    # ---- 6. package ----------------------------------------------------------------------
    unsigned = build_dir / "app-unsigned.apk"
    package_apk(project, res_apk, dex_dir, exploded, runtime_keys, unsigned)

    # ---- 7. sign -------------------------------------------------------------------------
    signed = build_dir / "RaptiBooks.apk"
    sign_apk(tools, unsigned, signed, app)
    run([aapt2, "dump", "badging", str(signed)], "aapt2 dump badging")
    log(f"APK ready: {signed} ({signed.stat().st_size / 1e6:.1f} MB)")
    return BuildOutput(signed, outputs, r_jar, lib_jars, android_jar, merged, res_apk, exploded, runtime_keys, resolved)


@dataclass
class BuildOutput:
    apk: Path
    module_classes: dict[str, Path]
    r_jar: Path
    lib_jars: list[Path]
    android_jar: Path
    manifest: Path
    resources: Path
    exploded: dict[str, Lib]
    runtime_keys: list[str]
    resolved: dict


def cmd_robotest(project: Project, refresh: bool, only: list[str] | None) -> None:
    """Robolectric tests of the app module, using the offline build outputs."""
    out = cmd_apk(project, refresh)
    resolved = out.resolved
    app = project.modules[project.app["module"]]
    if not app.test_dirs:
        log("no app tests")
        return
    robo = OUT / "robolectric"
    shutil.rmtree(robo / "config", ignore_errors=True)
    # Stubs for the androidx.test classes Robolectric calls.
    stub_src = sorted(str(p) for p in (TOOL_DIR.parent / "robolectric-stubs" / "src").rglob("*.java"))
    stub_out = robo / "stubs"
    shutil.rmtree(stub_out, ignore_errors=True)
    stub_out.mkdir(parents=True)
    run(["javac", "-nowarn", "--release", "17", "-d", str(stub_out), "-cp", str(out.android_jar)] + stub_src, "javac stubs")
    # Merged assets + the config file Robolectric reads (what AGP's includeAndroidResources writes).
    assets = robo / "assets"
    shutil.rmtree(assets, ignore_errors=True)
    assets.mkdir(parents=True)
    dirs = [m.assets_dir for m in project.modules.values() if m.assets_dir.is_dir()]
    dirs += [out.exploded[k].assets_dir for k in out.runtime_keys if out.exploded[k].assets_dir]
    for d in dirs:
        shutil.copytree(d, assets, dirs_exist_ok=True)
    # Robolectric's binary-resources mode serves assets from the resource APK.
    res_apk = robo / "resources-with-assets.ap_"
    shutil.copyfile(out.resources, res_apk)
    with zipfile.ZipFile(res_apk, "a") as zf:
        for f in sorted(assets.rglob("*")):
            if f.is_file():
                zf.write(f, "assets/" + f.relative_to(assets).as_posix(), compress_type=zipfile.ZIP_STORED)
    cfg = robo / "config" / "com" / "android" / "tools"
    cfg.mkdir(parents=True)
    (cfg / "test_config.properties").write_text(
        f"android_merged_manifest={out.manifest}\n"
        f"android_merged_assets={assets}\n"
        f"android_resource_apk={res_apk}\n"
        f"android_custom_package={app.namespace}\n"
    )
    test_jars = [Path(p) for p in resolved["test"]]
    stdlib = [Path(p) for p in stdlib_jars(resolved["libs"])]
    main_cp = list(out.module_classes.values()) + [out.r_jar] + out.lib_jars + stdlib
    tout = robo / "test-classes"
    kotlinc(resolved, app.safe_name + "-test", app.test_dirs, [stub_out] + main_cp + test_jars + [out.android_jar], tout, compose=True)
    for rdir in [app.path / "src/test/resources"]:
        if rdir.is_dir():
            shutil.copytree(rdir, tout, dirs_exist_ok=True)
    classes = sorted(
        str(f.relative_to(tout))[:-6].replace("/", ".")
        for f in tout.rglob("*Test.class") if "$" not in f.name)
    if only:
        classes = [c for c in classes if any(c.endswith(o) for o in only)]
    # android.* must be visible outside the sandbox too (AGP adds a mockable android.jar).
    cp = [tout, robo / "config", stub_out] + main_cp + test_jars + [out.android_jar]
    cmd = [java(), "-Xmx3g",
           f"-Drobolectric.dependency.repo.url={MAVEN_REPOS[0]}",
           "-Drobolectric.dependency.repo.id=central-mirror",
           f"-Djava.io.tmpdir={robo / 'tmp'}",
           "-cp", os.pathsep.join(str(p) for p in cp), "org.junit.runner.JUnitCore"] + classes
    (robo / "tmp").mkdir(exist_ok=True)
    log(f"running {len(classes)} Robolectric test classes")
    res = subprocess.run(cmd, capture_output=True, text=True)
    text = "\n".join(l for l in (res.stdout + res.stderr).splitlines() if not l.startswith("Picked up"))
    (robo / "last-run.txt").write_text(text)
    print(text[-25000:])
    if res.returncode != 0:
        die("Robolectric tests failed")
    log("Robolectric tests passed")


def stdlib_jars(libs: dict[str, dict]) -> list[str]:
    out = []
    for k in ("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations"):
        if k in libs:
            out.extend(libs[k]["files"])
    return out


def make_jar(src_dir: Path, jar: Path) -> None:
    tmp = jar.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in sorted(src_dir.rglob("*")):
            if f.is_file() and not f.name.startswith("."):
                zf.write(f, f.relative_to(src_dir).as_posix())
    tmp.replace(jar)


JAVA_RES_EXCLUDE = re.compile(
    r"(^META-INF/(MANIFEST\.MF|.*\.(SF|RSA|DSA|EC)|maven/.*|versions/.*|.*\.kotlin_module|INDEX\.LIST|"
    r"LICENSE.*|NOTICE.*|DEPENDENCIES|AL2\.0|LGPL2\.1|.*\.version|proguard/.*|com\.android\.tools/.*|"
    r"native-image/.*)$)|(\.class$)|(^module-info)|(/$)|(^lib/)|(\.(kt|java)$)")


def package_apk(project: Project, res_apk: Path, dex_dir: Path, exploded: dict[str, Lib],
                runtime_keys: list[str], dest: Path) -> None:
    abis = set(project.app["abis"])
    entries: dict[str, tuple[bytes | Path, bool]] = {}  # name -> (data, compress)

    def put(name: str, data: bytes | Path, compress: bool = True, override: bool = False) -> None:
        if name in entries and not override:
            return
        entries[name] = (data, compress)

    with zipfile.ZipFile(res_apk) as zf:
        for info in zf.infolist():
            put(info.filename, zf.read(info.filename), compress=info.compress_type != zipfile.ZIP_STORED)
    for dex in sorted(dex_dir.glob("*.dex")):
        put(dex.name, dex)
    # assets: app/modules first (override), then libraries
    for m in topo_order(project)[::-1]:
        if m.assets_dir.is_dir():
            for f in sorted(m.assets_dir.rglob("*")):
                if f.is_file():
                    name = "assets/" + f.relative_to(m.assets_dir).as_posix()
                    no_compress = f.suffix in (".traineddata", ".png", ".jpg", ".ttf", ".otf")
                    put(name, f, compress=not no_compress)
    for k in runtime_keys:
        lib = exploded[k]
        if lib.assets_dir:
            for f in sorted(lib.assets_dir.rglob("*")):
                if f.is_file():
                    put("assets/" + f.relative_to(lib.assets_dir).as_posix(), f)
        if lib.jni_dir:
            for f in sorted(lib.jni_dir.rglob("*.so")):
                abi = f.parent.name
                if abi in abis:
                    put(f"lib/{abi}/{f.name}", f)
        for jar in lib.jars:
            with zipfile.ZipFile(jar) as zf:
                for info in zf.infolist():
                    n = info.filename
                    if n.startswith("lib/") and n.endswith(".so"):
                        abi = n.split("/")[1]
                        if abi in abis:
                            put(n, zf.read(n))
                        continue
                    if JAVA_RES_EXCLUDE.search(n):
                        continue
                    put(n, zf.read(n))
    # Module java resources (non-class files in src/main/resources)
    for m in project.modules.values():
        rdir = m.path / "src/main/resources"
        if rdir.is_dir():
            for f in sorted(rdir.rglob("*")):
                if f.is_file():
                    put(f.relative_to(rdir).as_posix(), f)

    order = ["AndroidManifest.xml", "resources.arsc"]
    names = order + sorted(n for n in entries if n not in order)
    tmp = dest.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w") as zf:
        for n in names:
            if n not in entries:
                continue
            data, compress = entries[n]
            if n == "resources.arsc":
                compress = False
            raw = data.read_bytes() if isinstance(data, Path) else data
            zi = zipfile.ZipInfo(n, date_time=(1981, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED if compress else zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            zf.writestr(zi, raw, compresslevel=9 if compress else None)
    tmp.replace(dest)
    so = [n for n in entries if n.startswith("lib/")]
    log(f"packaged {len(entries)} entries ({len(so)} native libs)")


SIGNER_SRC = r"""
import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public class RbApkSign {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(a[2])) { ks.load(in, a[3].toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey(a[4], a[5].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[4]);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("CERT", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
            .setInputApk(new File(a[0])).setOutputApk(new File(a[1]))
            .setMinSdkVersion(Integer.parseInt(a[6]))
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
            .build().sign();
        ApkVerifier.Result r = new ApkVerifier.Builder(new File(a[1])).build().verify();
        System.out.println("verified=" + r.isVerified() + " v1=" + r.isVerifiedUsingV1Scheme()
            + " v2=" + r.isVerifiedUsingV2Scheme() + " v3=" + r.isVerifiedUsingV3Scheme());
        for (Object e : r.getErrors()) System.out.println("ERROR " + e);
        if (!r.isVerified()) System.exit(1);
    }
}
"""


def sign_apk(tools: dict, unsigned: Path, signed: Path, app: dict) -> None:
    signer_dir = CACHE / "signer"
    cls = signer_dir / "RbApkSign.class"
    if not cls.exists():
        signer_dir.mkdir(parents=True, exist_ok=True)
        (signer_dir / "RbApkSign.java").write_text(SIGNER_SRC)
        run(["javac", "-nowarn", "-cp", tools["bundletool"], "-d", str(signer_dir),
             str(signer_dir / "RbApkSign.java")], "javac signer")
    keystore = app["keystore"]
    if not keystore or not Path(keystore).exists():
        die("signing keystore not found")
    res = run([java(), "-cp", os.pathsep.join([str(signer_dir), tools["bundletool"]]), "RbApkSign",
               str(unsigned), str(signed), keystore, app["storePassword"], app["keyAlias"],
               app["keyPassword"], str(app["minSdk"])], "apksig")
    log("signed: " + res.stdout.strip().splitlines()[-1])


# --------------------------------------------------------------------------------------
# JVM unit tests for pure Kotlin modules
# --------------------------------------------------------------------------------------

def cmd_test(project: Project, refresh: bool, only: list[str] | None) -> None:
    resolved = load_resolved(project, refresh)
    libs_info = resolved["libs"]
    exploded: dict[str, Lib] = {}
    outputs: dict[str, Path] = {}
    failures = 0
    test_jars = [Path(p) for p in resolved["test"]]
    for m in topo_order(project):
        if m.kind != "jvm":
            continue
        cp, keys = module_classpath(project, m, libs_info, exploded, outputs)
        for k in keys:
            exploded.setdefault(k, explode(k, libs_info[k]))
        cp, _ = module_classpath(project, m, libs_info, exploded, outputs)
        cp += [Path(p) for p in stdlib_jars(libs_info)]
        out = OUT / "test-classes" / m.safe_name / "main"
        kotlinc(resolved, m.safe_name, m.src_dirs, cp, out, False)
        outputs[m.name] = out
        if not m.test_dirs or (only and m.name not in only):
            continue
        tout = OUT / "test-classes" / m.safe_name / "test"
        tcp = [out] + cp + test_jars
        kotlinc(resolved, m.safe_name + "-test", m.test_dirs, tcp, tout, False)
        classes = sorted(
            str(f.relative_to(tout))[:-6].replace("/", ".")
            for f in tout.rglob("*Test.class") if "$" not in f.name)
        if not classes:
            continue
        log(f"running {len(classes)} test classes in {m.name}")
        res = subprocess.run([java(), "-cp", os.pathsep.join(str(p) for p in [tout] + tcp),
                              "org.junit.runner.JUnitCore"] + classes, capture_output=True, text=True)
        out_text = "\n".join(l for l in res.stdout.splitlines() if not l.startswith("Picked up"))
        print(out_text[-15000:])
        if res.returncode != 0:
            failures += 1
    if failures:
        die(f"{failures} module(s) with failing tests")
    log("all tests passed")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["fetch", "apk", "test", "robotest"])
    ap.add_argument("--refresh", action="store_true", help="re-resolve dependencies")
    ap.add_argument("--module", action="append", help="limit tests to module(s)")
    args = ap.parse_args()
    project = load_project()
    if args.command == "fetch":
        cmd_fetch(project)
    elif args.command == "apk":
        cmd_apk(project, args.refresh)
    elif args.command == "robotest":
        cmd_robotest(project, args.refresh, args.module)
    else:
        cmd_test(project, args.refresh, args.module)


if __name__ == "__main__":
    main()
