#!/usr/bin/env python3
"""Builds docs/report/report.pdf from report.md.

Two things this does that a generic Markdown pipeline would not.

It expands {{include:path}} and {{image:path|caption}}, so the report's console blocks are the
measured output of a real run rather than numbers retyped into prose. A report that quotes its own
artefacts cannot drift from them.

And it depends on nothing outside the standard library plus a browser, because CLAUDE.md's second
rule is that a fresh clone and a JDK must do everything offline. The PDF step uses headless Chrome
or Chromium, which every development machine already has; if none is found, the HTML is still
written and any browser's "Print to PDF" produces the same document.
"""
import html
import pathlib
import re
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPORT = ROOT / "docs" / "report" / "report.md"

CHROME_CANDIDATES = [
    "google-chrome", "chromium", "chromium-browser", "chrome",
    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]


def find_chrome():
    for name in CHROME_CANDIDATES:
        found = shutil.which(name) or (name if pathlib.Path(name).exists() else None)
        if found:
            return found
    return None


def main():
    sys.path.insert(0, str(ROOT / "scripts"))
    import md2html

    html_path = REPORT.with_suffix(".html")
    pdf_path = REPORT.with_suffix(".pdf")

    md = md2html.expand_includes(REPORT.read_text(encoding="utf-8"), ROOT)
    body = md2html.convert(md)
    html_path.write_text(
        f'<!DOCTYPE html><html><head><meta charset="utf-8"><title>SKYFIX report</title>'
        f"<style>{md2html.CSS}</style></head><body>{body}</body></html>", encoding="utf-8")
    print(f"wrote {html_path.relative_to(ROOT)}")

    chrome = find_chrome()
    if chrome is None:
        print("no Chrome or Chromium found; open the HTML and print to PDF")
        return 0
    subprocess.run([chrome, "--headless", "--disable-gpu", "--no-sandbox",
                    "--no-pdf-header-footer", f"--print-to-pdf={pdf_path}",
                    html_path.as_uri()], check=True, capture_output=True)
    print(f"wrote {pdf_path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
