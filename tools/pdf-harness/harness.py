#!/usr/bin/env python3
"""Runs the real PDF extraction/structure/reflow code on the desktop JVM.

Requires a prior `python3 tools/offline-build/rbuild.py fetch` (for the compiler and libraries).
Usage: python3 tools/pdf-harness/harness.py file1.pdf [file2.pdf ...]
"""
import json, os, shutil, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path(os.environ.get("RB_CACHE", Path.home() / ".cache" / "raptibooks-offline"))
OUT = ROOT / "build" / "harness"
r = json.loads((CACHE / "resolved.json").read_text())
libs = r["libs"]
def files(key): return libs[key]["files"]
pdfbox_dir = next((CACHE / "exploded" / "com.tom-roush" / "pdfbox-android").glob("*/pdfbox-android-*"))
cp = [str(OUT / "shim")] + files("org.jetbrains.kotlin:kotlin-stdlib") + \
     [str(pdfbox_dir / "classes.jar"), r["tools"]["androidJar"]]
OUT.mkdir(parents=True, exist_ok=True)
shim_src = [str(p) for p in (Path(__file__).parent / "shim").rglob("*.java")]
subprocess.run(["javac", "-nowarn", "-d", str(OUT / "shim"), "-cp", r["tools"]["androidJar"]] + shim_src, check=True)

def kotlinc(out, sources, classpath):
    kc = ["java", "-cp", os.pathsep.join(r["kotlinc"]), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
          "-d", str(out), "-no-stdlib", "-jvm-target", "17", "-cp", os.pathsep.join(classpath)] + [str(s) for s in sources]
    res = subprocess.run(kc, capture_output=True, text=True)
    if res.returncode != 0:
        print(res.stdout, res.stderr); sys.exit(1)

# The engine modules are compiled here when their sources changed, so the harness always runs
# the current code.
for module in ["core/model", "core/structure", "core/reflow", "core/pdf"]:
    src = sorted((ROOT / module / "src/main/kotlin").rglob("*.kt"))
    out = OUT / module.replace("/", "-")
    stamp = out / ".stamp"
    newest = max(p.stat().st_mtime for p in src)
    if not stamp.exists() or stamp.stat().st_mtime < newest or any(
            Path(c).is_dir() and (Path(c) / ".stamp").exists() and (Path(c) / ".stamp").stat().st_mtime > stamp.stat().st_mtime
            for c in cp):
        shutil.rmtree(out, ignore_errors=True)
        kotlinc(out, src, cp)
        stamp.touch()
    cp.insert(1, str(out))
kotlinc(OUT / "classes", [Path(__file__).parent / "Harness.kt"], cp)
run = ["java", "-Xmx" + os.environ.get("HARNESS_XMX", "3g"), "-cp", os.pathsep.join([str(OUT / "classes")] + cp), "HarnessKt",
       str(pdfbox_dir / "assets")] + sys.argv[1:]
sys.exit(subprocess.run(run).returncode)
