# /// script
# requires-python = ">=3.10"
# dependencies = ["markdown==3.7", "pygments==2.18.0", "pypdf==5.1.0"]
# ///
"""Builds the Android developer guide as one PDF from the Markdown docs.

    uv run docs/tools/build_pdf.py            # from the repository root

Output: docs/build/poslink-android-guide-<version>.pdf (git-ignored). Needs Google Chrome (or
Chromium) 131 or newer; set CHROME=/path/to/chrome if it isn't found. The version comes from
PosLinkInfo.SDK_VERSION and the project link from poslink.repoUrl in android/gradle.properties.

Links between the included pages become links inside the PDF; links to other files in the
repository (the protocol spec, releasing) point at the repository on the web. A link to a
missing heading fails the build.
"""

from __future__ import annotations

import datetime
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import markdown
from pygments.formatters import HtmlFormatter
from pypdf import PdfReader, PdfWriter

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "docs" / "build"

# (file, key, title shown in the PDF; None keeps the page's own # heading)
CHAPTERS = [
    ("docs/README.md", "intro", "Introduction"),
    ("docs/android/setup.md", "setup", None),
    ("docs/android/cashier.md", "cashier", None),
    ("docs/android/terminal.md", "terminal", None),
    ("docs/android/testing.md", "testing", None),
    ("docs/payment-safety.md", "safety", None),
    ("docs/troubleshooting.md", "trouble", None),
]


@dataclass
class Chapter:
    src: str
    key: str
    title: str
    body: str = ""
    sections: list[tuple[str, str]] = field(default_factory=list)  # (id, text) of ## headings


def sdk_version() -> str:
    text = (ROOT / "android/poslink/src/main/kotlin/ly/dojoon/poslink/PosLinkInfo.kt").read_text()
    match = re.search(r'SDK_VERSION\s*=\s*"([^"]+)"', text)
    if not match:
        sys.exit("No SDK_VERSION in PosLinkInfo.kt")
    return match.group(1)


def repo_url() -> str:
    for line in (ROOT / "android/gradle.properties").read_text().splitlines():
        if line.startswith("poslink.repoUrl="):
            return line.split("=", 1)[1].strip().removesuffix("/")
    sys.exit("No poslink.repoUrl in android/gradle.properties")


def github_slug(text: str) -> str:
    """The id GitHub gives a heading, so links written for GitHub work here too."""
    text = html.unescape(text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)
    return text.replace(" ", "-")


def find_chrome() -> str:
    candidates = [
        os.environ.get("CHROME"),
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        shutil.which("google-chrome"),
        shutil.which("google-chrome-stable"),
        shutil.which("chromium"),
        shutil.which("chromium-browser"),
        shutil.which("chrome"),
    ]
    for c in candidates:
        if c and Path(c).exists():
            return c
    sys.exit("Chrome not found. Install Google Chrome or set CHROME=/path/to/chrome.")


def to_html(chapter: Chapter) -> None:
    text = (ROOT / chapter.src).read_text()

    # <details> would print collapsed; show it as an open box instead.
    text = re.sub(
        r"<details>\s*<summary>(.*?)</summary>",
        lambda m: f'<div class="aside" markdown="1">\n\n**{m.group(1).strip()}**\n',
        text,
    )
    text = text.replace("</details>", "</div>")

    md = markdown.Markdown(
        extensions=["tables", "fenced_code", "codehilite", "toc", "md_in_html", "sane_lists"],
        extension_configs={
            "codehilite": {"css_class": "hl", "guess_lang": False},
            "toc": {"slugify": lambda value, _sep: f"{chapter.key}--{github_slug(value)}"},
        },
    )
    body = md.convert(text)

    # "- [ ] item" checklists: print an empty box to tick by hand.
    body = re.sub(r"<li>(<p>)?\[([ xX])\]\s*", lambda m: (
        f'<li class="task">{m.group(1) or ""}<span class="box{" done" if m.group(2) != " " else ""}"></span>'
    ), body)

    # Replace the page's own # heading with the chapter header.
    tokens = md.toc_tokens
    if not tokens or tokens[0]["level"] != 1:
        sys.exit(f"{chapter.src} must start with a # heading")
    h1 = tokens[0]
    chapter.title = chapter.title or html.unescape(h1["name"])
    body = re.sub(r"<h1[^>]*>.*?</h1>", "", body, count=1, flags=re.S)
    chapter.sections = [(t["id"], html.unescape(t["name"])) for t in h1["children"]]
    chapter.body = body


def rewrite_links(chapters: list[Chapter], repo: str) -> None:
    by_src = {c.src: c for c in chapters}
    ids: set[str] = set()
    for c in chapters:
        ids.add(c.key)
        ids.update(re.findall(r'\sid="([^"]+)"', c.body))
    problems: list[str] = []

    for c in chapters:
        def fix(match: re.Match[str], c: Chapter = c) -> str:
            href = html.unescape(match.group(1))
            if re.match(r"^[a-z]+:", href):
                return match.group(0)
            path, _, frag = href.partition("#")
            if not path:
                target = f"{c.key}--{frag}"
            else:
                resolved = os.path.normpath(os.path.join(os.path.dirname(c.src), path))
                if resolved in by_src:
                    other = by_src[resolved].key
                    target = f"{other}--{frag}" if frag else other
                else:
                    if not (ROOT / resolved).exists():
                        problems.append(f"{c.src}: link to missing file {href}")
                    kind = "tree" if (ROOT / resolved).is_dir() else "blob"
                    url = f"{repo}/{kind}/main/{resolved}" + (f"#{frag}" if frag else "")
                    return f'href="{html.escape(url)}"'
            if target not in ids:
                problems.append(f"{c.src}: link to missing heading {href}")
            return f'href="#{html.escape(target)}"'

        c.body = re.sub(r'href="([^"]*)"', fix, c.body)

    if problems:
        sys.exit("Broken links:\n  " + "\n  ".join(problems))


def contents_html(chapters: list[Chapter], pages: dict[str, int] | None) -> str:
    def row(target: str, label: str) -> str:
        page = str(pages[target]) if pages else "00"
        return (
            f'<a class="row" href="#{target}"><span class="title">{label}</span>'
            f'<span class="leader"></span><span class="page">{page}</span></a>'
        )

    items = []
    for n, c in enumerate(chapters, 1):
        subs = "".join(f"<li>{row(sid, html.escape(name))}</li>" for sid, name in c.sections)
        label = f'<span class="num">{n}</span>{html.escape(c.title)}'
        items.append(f"<li>{row(c.key, label)}<ul>{subs}</ul></li>")
    return f'<section class="contents"><h1>Contents</h1><ol class="toc">{"".join(items)}</ol></section>'


def page_html(chapters: list[Chapter], version: str, repo: str, pages: dict[str, int] | None) -> str:
    css = (ROOT / "docs/tools/pdf.css").read_text()
    css = css.replace("FOOTER_TEXT", f"POS Link Android SDK {version}")
    # Each chapter is a named page, so its title can be in the page header.
    for c in chapters:
        title = c.title.replace("\\", "\\\\").replace('"', '\\"')
        css += (
            f"\n#{c.key} {{ page: {c.key}; }}\n"
            f'@page {c.key} {{ @top-right {{ content: "{title}"; '
            "font: 7.5pt -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Helvetica Neue', Arial, sans-serif; "
            "color: #8a939e; } }\n"
        )
    css += HtmlFormatter(style="xcode").get_style_defs(".hl")

    today = datetime.date.today()
    coords = f"ly.dojoon.poslink:poslink-android:{version}"
    cover = f"""
<section class="cover">
  <div class="band"></div>
  <div class="brand">Dojoon · POS Link</div>
  <div class="title">Android SDK<br>Developer Guide</div>
  <div class="subtitle">Connect a cashier app to a Sunmi P3 payment terminal over USB or Bluetooth,
  and take card payments safely.</div>
  <dl class="facts">
    <dt>Version</dt><dd>{version}</dd>
    <dt>Package</dt><dd><code>{coords}</code></dd>
    <dt>Requires</dt><dd>Android 7.0 (API 24)+, compileSdk 34+, Kotlin 2.0+</dd>
    <dt>Source</dt><dd><a href="{html.escape(repo)}">{html.escape(repo.split("://", 1)[-1])}</a></dd>
    <dt>Date</dt><dd>{today:%d %B %Y}</dd>
  </dl>
  <div class="bottom"><span>Apache License 2.0</span><span>Generated from the docs folder of the SDK repository</span></div>
</section>"""

    chapters_html = "".join(
        f'<section class="chapter" id="{c.key}"><header class="chapter-head">'
        f'<div class="eyebrow">Chapter {n}</div><h1>{html.escape(c.title)}</h1></header>'
        f"{c.body}</section>"
        for n, c in enumerate(chapters, 1)
    )
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<title>POS Link Android SDK {version}: Developer Guide</title>
<style>{css}</style></head>
<body>{cover}{contents_html(chapters, pages)}{chapters_html}</body></html>"""


def print_pdf(chrome: str, html_file: Path, pdf_file: Path) -> None:
    with tempfile.TemporaryDirectory() as profile:
        result = subprocess.run(
            [
                chrome,
                "--headless",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                f"--user-data-dir={profile}",
                "--no-pdf-header-footer",
                "--generate-pdf-document-outline",
                f"--print-to-pdf={pdf_file}",
                html_file.as_uri(),
            ],
            capture_output=True,
            text=True,
            timeout=180,
        )
    if result.returncode != 0 or not pdf_file.exists():
        sys.exit(f"Chrome failed ({result.returncode}):\n{result.stderr}")


def heading_pages(pdf_file: Path, targets: list[str]) -> dict[str, int]:
    """Page number (1-based) of each heading, from the PDF's named destinations."""
    reader = PdfReader(pdf_file)
    dests = {str(name).lstrip("/"): d for name, d in reader.named_destinations.items()}
    pages = {}
    for t in targets:
        if t not in dests:
            sys.exit(f"Chrome didn't keep the link target {t}; can't number the contents.")
        pages[t] = reader.get_destination_page_number(dests[t]) + 1
    return pages


def main() -> None:
    version = sdk_version()
    repo = repo_url()
    chrome = find_chrome()

    chapters = [Chapter(src, key, title or "") for src, key, title in CHAPTERS]
    for c in chapters:
        to_html(c)
    rewrite_links(chapters, repo)
    targets = [t for c in chapters for t in [c.key, *(sid for sid, _ in c.sections)]]

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    html_file = OUT_DIR / "guide.html"
    draft = OUT_DIR / "guide-draft.pdf"
    out = OUT_DIR / f"poslink-android-guide-{version}.pdf"

    # Pass 1 finds the pages; pass 2 writes them into the contents (same layout, so they don't move).
    html_file.write_text(page_html(chapters, version, repo, None))
    print_pdf(chrome, html_file, draft)
    pages = heading_pages(draft, targets)

    html_file.write_text(page_html(chapters, version, repo, pages))
    print_pdf(chrome, html_file, draft)
    if heading_pages(draft, targets) != pages:
        sys.exit("Page numbers moved between passes; check the contents layout.")

    writer = PdfWriter(clone_from=draft)
    writer.add_metadata({
        "/Title": f"POS Link Android SDK {version}: Developer Guide",
        "/Author": "Dojoon",
        "/Creator": "docs/tools/build_pdf.py (Chrome)",
        "/Subject": "Connect a cashier app to a Sunmi P3 payment terminal over USB or Bluetooth.",
    })
    with out.open("wb") as f:
        writer.write(f)
    draft.unlink()

    total = len(PdfReader(out).pages)
    print(f"{out.relative_to(ROOT)} ({total} pages, {out.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
