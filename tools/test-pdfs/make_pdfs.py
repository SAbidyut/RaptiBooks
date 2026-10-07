#!/usr/bin/env python3
"""Regenerates the math test PDFs in app/src/test/resources/pdf from the HTML sources here.

Chromium prints the HTML; afterwards the ToUnicode map of the "SymPi" symbol font (Liberation
Sans) is pointed into the private use area so that its glyphs ("=", "×") have no usable text,
as with the Mathematical Pi fonts of many typeset textbooks.
"""
import re, subprocess, sys
from pathlib import Path
from pypdf import PdfReader, PdfWriter
from pypdf.generic import NameObject

HERE = Path(__file__).resolve().parent
OUT = HERE.parents[1] / "app/src/test/resources/pdf"
CHROME = "/opt/pw-browsers/chromium-1194/chrome-linux/chrome"

def print_pdf(html: Path, pdf: Path):
    subprocess.run([CHROME, "--headless", "--no-sandbox", "--disable-gpu", "--no-pdf-header-footer",
                    "--allow-file-access-from-files", f"--print-to-pdf={pdf}", html.as_uri()], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def strip_unicode(pdf: Path, font_marker: str):
    """Points the font's ToUnicode entries into the private use area (no usable text)."""
    r = PdfReader(pdf); w = PdfWriter()
    done = set()
    for page in r.pages:
        fonts = page.get("/Resources", {}).get("/Font", {})
        for ref in fonts.values():
            f = ref.get_object()
            if font_marker in str(f.get("/BaseFont", "")) and "/ToUnicode" in f and id(f) not in done:
                done.add(id(f))
                cmap = f["/ToUnicode"].get_object()
                data = cmap.get_data().decode("latin-1")
                data = re.sub(r"(<[0-9A-Fa-f]{4}>\s*)<([0-9A-Fa-f]{4})>",
                              lambda m: m.group(1) + "<E0" + m.group(2)[2:] + ">", data)
                cmap.set_data(data.encode("latin-1"))
        w.add_page(page)
    with open(pdf, "wb") as fh: w.write(fh)

print_pdf(HERE / "math_textbook.html", OUT / "math_textbook.pdf")
strip_unicode(OUT / "math_textbook.pdf", "LiberationSans")
print_pdf(HERE / "bangla_math.html", OUT / "bangla_math.pdf")
print("ok")
