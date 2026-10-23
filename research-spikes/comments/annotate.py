"""Isolated classic-comment writer. Patches an original copy, not an analysis model."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from lxml import etree

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
REL = 'http://schemas.openxmlformats.org/package/2006/relationships'
CT = 'http://schemas.openxmlformats.org/package/2006/content-types'
COMMENT_REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments'
COMMENT_TYPE = 'application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml'

def parse(raw):
    parser = etree.XMLParser(resolve_entities=False, no_network=True, load_dtd=False)
    root = etree.fromstring(raw, parser)
    if root.getroottree().docinfo.doctype:
        raise ValueError('DOCTYPE unsupported')
    return root

def serialize(root):
    return etree.tostring(root, encoding='UTF-8', xml_declaration=True, standalone=True)

def annotate(original, expected_hash, body_paragraph, message, anchor_path=None):
    if hashlib.sha256(original).hexdigest() != expected_hash:
        raise ValueError('Source hash mismatch')
    if body_paragraph < 1 or not message.strip():
        raise ValueError('Invalid anchor or empty message')
    with ZipFile(io.BytesIO(original)) as package:
        entries = package.infolist()
        if len(entries) != len({e.filename for e in entries}):
            raise ValueError('Duplicate ZIP entries')
        parts = {e.filename: package.read(e) for e in entries}
    if any(n.startswith('_xmlsignatures/') or 'commentsExtended' in n or 'commentsIds' in n for n in parts):
        raise ValueError('Signed packages and modern comment metadata need separate validation')
    doc = parse(parts['word/document.xml'])
    rels = parse(parts['word/_rels/document.xml.rels'])
    types = parse(parts['[Content_Types].xml'])
    ns = {'w': W}
    if anchor_path is None:
        paragraphs = doc.xpath('/w:document/w:body/w:p', namespaces=ns)
        if body_paragraph > len(paragraphs):
            raise ValueError('Paragraph address out of bounds')
        paragraph = paragraphs[body_paragraph - 1]
    else:
        paragraph = doc.getroottree().getroot()
        for index in anchor_path:
            children = [child for child in paragraph if isinstance(child.tag, str)]
            if index < 0 or index >= len(children):
                raise ValueError('Anchor path out of bounds')
            paragraph = children[index]
        if paragraph.tag != f'{{{W}}}p':
            raise ValueError('Anchor path must end at a paragraph')
    runs = paragraph.xpath('./w:r[w:t]', namespaces=ns)
    if not runs or not ''.join(''.join(r.xpath('./w:t/text()', namespaces=ns)) for r in runs).strip():
        raise ValueError('Anchor requires direct visible text')
    if paragraph.xpath('.//w:commentRangeStart|.//w:commentRangeEnd|.//w:commentReference', namespaces=ns):
        raise ValueError('Overlapping existing comment anchor is outside this probe')
    comments = parse(parts['word/comments.xml']) if 'word/comments.xml' in parts else etree.Element(f'{{{W}}}comments', nsmap={'w': W})
    if comments.tag != f'{{{W}}}comments':
        raise ValueError('Unexpected comments root')
    existing = [r for r in rels if r.get('Type') == COMMENT_REL]
    if existing:
        if len(existing) != 1 or existing[0].get('Target') != 'comments.xml' or existing[0].get('TargetMode') == 'External' or 'word/comments.xml' not in parts:
            raise ValueError('Unsupported comments relationship')
    elif 'word/comments.xml' in parts:
        raise ValueError('Orphan comments part')
    else:
        used = {r.get('Id') for r in rels}
        number = 1
        while f'rId{number}' in used:
            number += 1
        etree.SubElement(rels, f'{{{REL}}}Relationship', Id=f'rId{number}', Type=COMMENT_REL, Target='comments.xml')
    overrides = [n for n in types if n.get('PartName') == '/word/comments.xml']
    if overrides:
        if len(overrides) != 1 or overrides[0].get('ContentType') != COMMENT_TYPE:
            raise ValueError('Invalid comments content type')
    else:
        etree.SubElement(types, f'{{{CT}}}Override', PartName='/word/comments.xml', ContentType=COMMENT_TYPE)
    ids = [int(n.get(f'{{{W}}}id')) for root in (doc, comments) for n in root.iter()
           if n.tag in {f'{{{W}}}{tag}' for tag in ('comment','commentRangeStart','commentRangeEnd','commentReference')}]
    identifier = str(max(ids, default=-1) + 1)
    comment = etree.SubElement(comments, f'{{{W}}}comment', {
        f'{{{W}}}id': identifier, f'{{{W}}}author': '论文格式筛查',
        f'{{{W}}}date': datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00','Z')})
    cp = etree.SubElement(comment, f'{{{W}}}p')
    cr = etree.SubElement(cp, f'{{{W}}}r')
    etree.SubElement(cr, f'{{{W}}}t').text = message
    start = etree.Element(f'{{{W}}}commentRangeStart', {f'{{{W}}}id': identifier})
    end = etree.Element(f'{{{W}}}commentRangeEnd', {f'{{{W}}}id': identifier})
    reference = etree.Element(f'{{{W}}}r')
    etree.SubElement(reference, f'{{{W}}}commentReference', {f'{{{W}}}id': identifier})
    runs[0].addprevious(start)
    runs[-1].addnext(end)
    end.addnext(reference)
    changes = {'word/document.xml': serialize(doc), 'word/comments.xml': serialize(comments)}
    if not existing:
        changes['word/_rels/document.xml.rels'] = serialize(rels)
    if not overrides:
        changes['[Content_Types].xml'] = serialize(types)
    output = io.BytesIO()
    with ZipFile(output, 'w', compression=ZIP_DEFLATED) as package:
        for entry in entries:
            package.writestr(entry, changes.pop(entry.filename, parts[entry.filename]))
        for name, data in changes.items():
            package.writestr(name, data)
    return output.getvalue()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    expected = '3c9c3dc012b779b4ebd20ea35cb86142d73fb911c7733e3b0d1789ce2d6ac2b2'
    result = annotate(args.source.read_bytes(), expected, 115,
        '【PAGE-01 页边距】第7节右边距为1418 twips（约2.5 cm），学校要求2.6 cm（1474 twips）。'
        '请在“布局/页面布局 → 页边距 → 自定义页边距”中将右边距设置为2.6厘米，应用于本节。'
        '本批注锚定本节标题，仅提示本节页边距问题，不表示标题文字有误。')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('xb') as file:
        file.write(result)
    print(args.output.resolve())
