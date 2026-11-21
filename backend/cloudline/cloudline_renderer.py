"""Render a finished plain-text freight email with the AEIOU HTML layout."""

import re
from base64 import b64encode
from functools import cache
from html import escape
from pathlib import Path
from string import Template


LAYOUT_DIR = Path(__file__).parent
SIGNATURE_STYLE = "color: #304c5a; font-weight: 700;"


@cache
def _load_layout() -> Template:
    filename = LAYOUT_DIR / "mail-designs" / "cloudline.html"
    return Template(filename.read_text(encoding="utf-8"))


@cache
def _cloud_image_url() -> str:
    image = LAYOUT_DIR / "mail-designs" / "assets" / "clouds.webp"
    return "data:image/webp;base64," + b64encode(image.read_bytes()).decode("ascii")


def render_mail_html(
    body: str,
    *,
    all_in_total: str = "",
    language: str = "en",
    variant: str = "cloudline",
    route: tuple[str, str] | None = None,
) -> str:
    """Render Cloudline with an optional route and exact total."""
    if variant != "cloudline":
        raise ValueError(f"Unknown AEIOU mail design: {variant}")
    layout = _load_layout()

    body = body.replace("\r\n", "\n").replace("\r", "\n").strip()
    paragraphs = re.split(r"\n(?:[^\S\n]*\n)+", body) if body else []
    total = all_in_total if all_in_total and body.count(all_in_total) == 1 else ""
    rendered = []

    for paragraph_index, paragraph in enumerate(paragraphs):
        is_last_paragraph = paragraph_index == len(paragraphs) - 1
        lines = paragraph.split("\n")
        rendered_lines = []
        for line_index, line in enumerate(lines):
            line_html = escape(line)
            if total and total in line:
                before, after = line.split(total, 1)
                line_html = (
                    f'{escape(before)}<strong>'
                    f'{escape(total)}</strong>{escape(after)}'
                )
            if (
                is_last_paragraph
                and line_index == len(lines) - 1
                and line.strip() == "Team AEIOU"
            ):
                line_html = f'<span style="{SIGNATURE_STYLE}">{line_html}</span>'
            rendered_lines.append(line_html)

        paragraph_html = "<br>\n              ".join(rendered_lines)
        margin = "0" if is_last_paragraph else "0 0 16px"
        rendered.append(f'<p style="margin: {margin};">{paragraph_html}</p>')

    lane_html = "Freight offer"
    if route:
        origin, destination = (stop.strip() for stop in route)
        if origin and destination:
            dotted_line = (
                '<span style="display:inline-block;width:9px;margin:0 2px;'
                'border-top:1px dotted #607889;vertical-align:.25em;"></span>'
            )
            separator = (
                '<span role="img" aria-label="→" style="color:#405a6b;'
                'font-family:\'Apple Symbols\',\'Segoe UI Symbol\',\'DejaVu Sans\',sans-serif;'
                'font-variant-emoji:text;font-size:.74em;font-weight:400;'
                'letter-spacing:0;display:inline-block;white-space:nowrap;vertical-align:.16em;">'
                f'{dotted_line}&#x2708;&#xFE0E;{dotted_line}</span>'
            )
            lane_html = f"{escape(origin)} {separator} {escape(destination)}"

    return layout.substitute(
        language=escape(language),
        lane=lane_html,
        message_html="\n              ".join(rendered),
        cloud_image_url=_cloud_image_url(),
    )
