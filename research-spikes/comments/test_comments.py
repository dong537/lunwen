import hashlib
import io
import os
from pathlib import Path
import unittest
from zipfile import ZipFile
from lxml import etree
from annotate import annotate, W

SOURCE = Path(os.environ['SCHOOL_DOCX'])
HASH = '3c9c3dc012b779b4ebd20ea35cb86142d73fb911c7733e3b0d1789ce2d6ac2b2'
NS = {'w': W}

def parts(data):
    with ZipFile(io.BytesIO(data)) as z:
        return {n: z.read(n) for n in z.namelist()}

class CommentTests(unittest.TestCase):
    def test_table_paragraph_uses_structural_path(self):
        original = SOURCE.read_bytes()
        with ZipFile(io.BytesIO(original)) as package:
            data = package.read('word/document.xml')
            entries = package.infolist()
            parts_before = {entry.filename: package.read(entry) for entry in entries}
        root = etree.fromstring(data)
        table_paragraph = root.xpath('//w:tbl//w:p', namespaces=NS)[0]
        path = []
        node = table_paragraph
        while node is not root:
            parent = node.getparent()
            path.append(sum(isinstance(child.tag, str) for child in parent[:parent.index(node)]))
            node = parent
        path.reverse()
        result = annotate(original, HASH, 1, '【表格】该表格段落的格式需要复核。', anchor_path=path)
        out = parts(result)['word/document.xml']
        output_root = etree.fromstring(out)
        self.assertEqual(1, len(output_root.xpath('//w:tbl//w:p//w:commentRangeStart', namespaces=NS)))
        self.assertEqual(0, len(output_root.xpath('/w:document/w:body/w:p[1]//w:commentRangeStart', namespaces=NS)))

    def test_duplicate_text_elsewhere_does_not_change_structural_anchor(self):
        original = SOURCE.read_bytes()
        with ZipFile(io.BytesIO(original)) as package:
            entries = package.infolist()
            data = {entry.filename: package.read(entry) for entry in entries}
        doc = etree.fromstring(data['word/document.xml'])
        paragraphs = doc.xpath('/w:document/w:body/w:p', namespaces=NS)
        target_text = ''.join(paragraphs[114].xpath('.//w:t/text()', namespaces=NS))
        duplicate = etree.SubElement(paragraphs[0], f'{{{W}}}r')
        etree.SubElement(duplicate, f'{{{W}}}t').text = target_text
        data['word/document.xml'] = etree.tostring(doc, encoding='UTF-8', xml_declaration=True, standalone=True)
        modified = io.BytesIO()
        with ZipFile(modified, 'w') as package:
            for entry in entries:
                package.writestr(entry, data[entry.filename])
        modified_bytes = modified.getvalue()
        result = annotate(modified_bytes, hashlib.sha256(modified_bytes).hexdigest(), 115, '结构定位意见')
        root = etree.fromstring(parts(result)['word/document.xml'])
        self.assertEqual(0, len(root.xpath('/w:document/w:body/w:p[1]//w:commentRangeStart', namespaces=NS)))
        self.assertEqual(1, len(root.xpath('/w:document/w:body/w:p[115]//w:commentRangeStart', namespaces=NS)))

    def test_real_file_only_required_parts_change_and_anchor_roundtrips(self):
        original = SOURCE.read_bytes()
        result = annotate(original, HASH, 115, '【页边距】第7节右边距为1418 twips，约2.5 cm；学校要求2.6 cm（1474 twips）。请在本节页面设置中修改右边距。')
        before, after = parts(original), parts(result)
        self.assertEqual(set(after) - set(before), {'word/comments.xml'})
        allowed = {'word/document.xml', 'word/_rels/document.xml.rels', '[Content_Types].xml'}
        for name in before:
            if name not in allowed:
                self.assertEqual(before[name], after[name], name)
        doc = etree.fromstring(after['word/document.xml'])
        comments = etree.fromstring(after['word/comments.xml'])
        self.assertEqual(len(comments), 1)
        identifier = comments[0].get(f'{{{W}}}id')
        for tag in ['commentRangeStart', 'commentRangeEnd', 'commentReference']:
            nodes = doc.xpath('//w:' + tag, namespaces=NS)
            self.assertEqual(len(nodes), 1)
            self.assertEqual(nodes[0].get(f'{{{W}}}id'), identifier)
        p = doc.xpath('/w:document/w:body/w:p[115]', namespaces=NS)[0]
        self.assertTrue(p.xpath('./w:commentRangeStart', namespaces=NS))
        # Removing only inserted annotation nodes restores the full original document tree.
        for node in doc.xpath('//w:commentRangeStart|//w:commentRangeEnd', namespaces=NS):
            node.getparent().remove(node)
        for node in doc.xpath('//w:r[w:commentReference]', namespaces=NS):
            node.getparent().remove(node)
        self.assertEqual(etree.tostring(etree.fromstring(before['word/document.xml']),method='c14n'),etree.tostring(doc,method='c14n'))
        self.assertEqual(original, SOURCE.read_bytes())

    def test_existing_comment_preserved_and_new_id_unique(self):
        once = annotate(SOURCE.read_bytes(), HASH, 115, '已有批注意见')
        twice = annotate(once, hashlib.sha256(once).hexdigest(), 117, '第二条意见')
        before = etree.fromstring(parts(once)['word/comments.xml'])
        after = etree.fromstring(parts(twice)['word/comments.xml'])
        self.assertEqual(len(after), 2)
        self.assertEqual(etree.tostring(before[0]), etree.tostring(after[0]))
        self.assertEqual(len({n.get(f'{{{W}}}id') for n in after}), 2)

    def test_wrong_source_hash_rejected(self):
        with self.assertRaisesRegex(ValueError, 'hash'):
            annotate(SOURCE.read_bytes(), '0'*64, 115, '意见')

    def test_empty_paragraph_rejected_instead_of_wrong_anchor(self):
        with self.assertRaisesRegex(ValueError, 'text'):
            annotate(SOURCE.read_bytes(), HASH, 120, '意见')

    def test_textbox_projected_path_is_rejected_until_branch_mapping_exists(self):
        original = SOURCE.read_bytes()
        with ZipFile(io.BytesIO(original)) as package:
            entries = package.infolist()
            data = {entry.filename: package.read(entry) for entry in entries}
        root = etree.fromstring(data['word/document.xml'])
        projected = etree.fromstring(data['word/document.xml'])
        for alternate in reversed(projected.xpath('//mc:AlternateContent', namespaces={'mc': 'http://schemas.openxmlformats.org/markup-compatibility/2006'})):
            fallback = alternate.find('{http://schemas.openxmlformats.org/markup-compatibility/2006}Fallback')
            if fallback is None:
                continue
            parent = alternate.getparent()
            position = parent.index(alternate)
            for child in list(fallback):
                parent.insert(position, child)
                position += 1
            parent.remove(alternate)
        textbox_paragraph = projected.xpath('//w:txbxContent//w:p[.//w:t]', namespaces=NS)[0]
        path = []
        node = textbox_paragraph
        while node is not projected:
            parent = node.getparent()
            path.append(sum(isinstance(child.tag, str) for child in parent[:parent.index(node)]))
            node = parent
        path.reverse()
        with self.assertRaisesRegex(ValueError, 'out of bounds'):
            annotate(original, HASH, 1, '【文本框】此处格式需要复核。', anchor_path=path)

if __name__ == '__main__':
    unittest.main()
