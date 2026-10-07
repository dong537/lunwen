"""Read-only OLE inventory; derived equation bytes remain in ignored private-output."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / '.deps'))
import olefile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('docx', type=Path)
    args = parser.parse_args()
    original = args.docx.read_bytes()
    rows = []
    output = ROOT / 'private-output'
    output.mkdir(exist_ok=True)
    with ZipFile(io.BytesIO(original)) as package:
        for part in package.namelist():
            if not part.startswith('word/embeddings/') or part.endswith('/'):
                continue
            data = package.read(part)
            with olefile.OleFileIO(io.BytesIO(data)) as storage:
                streams = storage.listdir()
                if not storage.exists('Equation Native'):
                    raise ValueError(f'Unsupported embedded object without Equation Native: {part}')
                native = storage.openstream('Equation Native').read()
            header_size = struct.unpack_from('<H', native, 0)[0]
            payload_size = struct.unpack_from('<I', native, 8)[0]
            if header_size != 28 or payload_size != len(native) - header_size:
                raise ValueError(f'Unexpected Equation Native header/size: {part}')
            payload = native[header_size:]
            if payload[0] not in (3, 5):
                raise ValueError(f'Unsupported MTEF version {payload[0]}: {part}')
            (output / (Path(part).stem + '.mtef')).write_bytes(payload)
            rows.append(dict(part=part, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
                             streams=streams, nativeBytes=len(native), headerBytes=header_size,
                             payloadBytes=payload_size, mtefVersion=payload[0],
                             nativeSha256=hashlib.sha256(native).hexdigest()))
    if args.docx.read_bytes() != original:
        raise ValueError('Original DOCX changed during read')
    report = dict(sourceSha256=hashlib.sha256(original).hexdigest(), objects=rows)
    (ROOT / 'evidence').mkdir(exist_ok=True)
    (ROOT / 'evidence/ole-inventory.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
