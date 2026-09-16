"""Minimal Markdown to HTML for the SKYFIX report.

Deliberately covers only the constructs the report uses -- headings, tables, fenced code,
bold, inline code, bullets, rules and paragraphs -- because a general converter is a project
and this one has to be readable enough to trust.
"""
import html
import re
import sys

def inline(text):
    text = html.escape(text, quote=False)
    text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
    text = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)
    text = re.sub(r'(?<![\w*])\*([^*\n]+)\*(?![\w*])', r'<em>\1</em>', text)
    return text

def expand_includes(md, base):
    """Inline {{include:path}} as a code block and {{image:path}} as a figure.

    The report quotes measured output, and a number retyped from a test run is a number that can
    drift from it. Including the file means the document cannot disagree with the artefact.
    """
    def include(match):
        text = (base / match.group(1)).read_text(encoding='utf-8').rstrip()
        return '```\n' + text + '\n```'

    def image(match):
        path, caption = match.group(1), match.group(2)
        return f'<figure><img src="{path}"/><figcaption>{html.escape(caption)}</figcaption></figure>'

    md = re.sub(r'\{\{include:([^}]+)\}\}', include, md)
    md = re.sub(r'\{\{image:([^|]+)\|([^}]*)\}\}', image, md)
    return md

def convert(md):
    out, i, lines = [], 0, md.split('\n')
    while i < len(lines):
        line = lines[i]
        if line.startswith('```'):
            i += 1
            block = []
            while i < len(lines) and not lines[i].startswith('```'):
                block.append(html.escape(lines[i])); i += 1
            out.append('<pre><code>' + '\n'.join(block) + '</code></pre>'); i += 1
        elif re.match(r'^---+\s*$', line):
            out.append('<hr/>'); i += 1
        elif line.startswith('#'):
            level = len(line) - len(line.lstrip('#'))
            out.append(f'<h{level}>{inline(line[level:].strip())}</h{level}>'); i += 1
        elif line.strip().startswith('|'):
            rows = []
            while i < len(lines) and lines[i].strip().startswith('|'):
                rows.append(lines[i].strip()); i += 1
            cells = [[c.strip() for c in r.strip('|').split('|')] for r in rows]
            body = [r for r in cells if not all(re.match(r'^:?-+:?$', c or '-') for c in r)]
            head, rest = body[0], body[1:]
            t = ['<table><thead><tr>'] + [f'<th>{inline(c)}</th>' for c in head] + ['</tr></thead><tbody>']
            for r in rest:
                t.append('<tr>' + ''.join(f'<td>{inline(c)}</td>' for c in r) + '</tr>')
            t.append('</tbody></table>')
            out.append(''.join(t))
        elif line.strip().startswith('- '):
            items = []
            while i < len(lines) and lines[i].strip().startswith('- '):
                item = lines[i].strip()[2:]
                i += 1
                while i < len(lines) and lines[i].startswith('  ') and lines[i].strip() \
                        and not lines[i].strip().startswith('- '):
                    item += ' ' + lines[i].strip(); i += 1
                items.append(f'<li>{inline(item)}</li>')
            out.append('<ul>' + ''.join(items) + '</ul>')
        elif line.startswith('<figure'):
            out.append(line); i += 1
        elif not line.strip():
            i += 1
        else:
            para = []
            while i < len(lines) and lines[i].strip() and not lines[i].startswith('#') \
                    and not lines[i].strip().startswith('|') and not lines[i].startswith('```') \
                    and not lines[i].strip().startswith('- ') \
                    and not re.match(r'^---+\s*$', lines[i]):
                para.append(lines[i].strip()); i += 1
            out.append('<p>' + inline(' '.join(para)) + '</p>')
    return '\n'.join(out)

CSS = """
@page { size: A4; margin: 20mm 18mm; }
body { font-family: "Liberation Serif", Georgia, serif; font-size: 10.5pt; line-height: 1.45;
       color: #111; }
h1 { font-size: 20pt; border-bottom: 2px solid #333; padding-bottom: 6pt; }
h2 { font-size: 14pt; margin-top: 20pt; border-bottom: 1px solid #bbb; padding-bottom: 3pt; }
h3 { font-size: 11.5pt; margin-top: 14pt; }
table { border-collapse: collapse; width: 100%; margin: 9pt 0; font-size: 9pt; }
th, td { border: 1px solid #999; padding: 3pt 5pt; text-align: left; vertical-align: top; }
th { background: #eee; }
code { font-family: "Liberation Mono", monospace; font-size: 9pt; background: #f2f2f2;
       padding: 0 2px; }
pre { background: #f6f6f6; border: 1px solid #ddd; padding: 6pt; font-size: 8.5pt;
      white-space: pre-wrap; }
pre code { background: none; }
figure { margin: 10pt 0; page-break-inside: avoid; text-align: center; }
figure img { max-width: 100%; border: 1px solid #ccc; }
figcaption { font-size: 9pt; color: #444; margin-top: 3pt; font-style: italic; }
h2 { page-break-after: avoid; }
table { page-break-inside: avoid; }
hr { border: none; border-top: 1px solid #ccc; margin: 14pt 0; }
"""

if __name__ == '__main__':
    import pathlib
    source = pathlib.Path(sys.argv[1])
    md = expand_includes(source.read_text(encoding='utf-8'), pathlib.Path(sys.argv[3]))
    body = convert(md)
    open(sys.argv[2], 'w', encoding='utf-8').write(
        f'<!DOCTYPE html><html><head><meta charset="utf-8">'
        f'<title>SKYFIX report</title><style>{CSS}</style></head><body>{body}</body></html>')
    print('wrote', sys.argv[2])
