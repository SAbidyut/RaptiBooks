#!/usr/bin/env python3
"""Runs the real PDF extraction/structure/reflow code on the desktop JVM.

Requires a prior `python3 tools/offline-build/rbuild.py apk` (for compiled module classes).
Usage: python3 tools/pdf-harness/harness.py file1.pdf [file2.pdf ...]
"""
import json, os, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path(os.environ.get("RB_CACHE", Path.home() / ".cache" / "raptibooks-offline"))
OUT = ROOT / "build" / "harness"
r = json.loads((CACHE / "resolved.json").read_text())
libs = r["libs"]
def files(key): return libs[key]["files"]
pdfbox_dir = next((CACHE / "exploded" / "com.tom-roush" / "pdfbox-android").glob("*/pdfbox-android-*"))
def module_classes(m):
    # Pure-Kotlin modules: `rbuild.py test` output is fresher than the last APK build, if newer.
    full, quick = ROOT / "build/offline/classes" / m, ROOT / "build/offline/test-classes" / m / "main"
    if quick.is_dir() and (not full.is_dir() or quick.stat().st_mtime > full.stat().st_mtime):
        return quick
    return full
classes = [module_classes(m) for m in ["core-model", "core-structure", "core-reflow", "core-pdf"]]
cp = [str(OUT / "shim")] + [str(c) for c in classes] + files("org.jetbrains.kotlin:kotlin-stdlib") + \
     [str(pdfbox_dir / "classes.jar"), r["tools"]["androidJar"]]
OUT.mkdir(parents=True, exist_ok=True)
shim_src = [str(p) for p in (Path(__file__).parent / "shim").rglob("*.java")]
subprocess.run(["javac", "-nowarn", "-d", str(OUT / "shim"), "-cp", r["tools"]["androidJar"]] + shim_src, check=True)
kc = ["java", "-cp", os.pathsep.join(r["kotlinc"]), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
      "-d", str(OUT / "classes"), "-no-stdlib", "-jvm-target", "17", "-cp", os.pathsep.join(cp),
      str(Path(__file__).parent / "Harness.kt")]
res = subprocess.run(kc, capture_output=True, text=True)
if res.returncode != 0:
    print(res.stdout, res.stderr); sys.exit(1)
run = ["java", "-Xmx1g", "-cp", os.pathsep.join([str(OUT / "classes")] + cp), "HarnessKt",
       str(pdfbox_dir / "assets")] + sys.argv[1:]
sys.exit(subprocess.run(run).returncode)
