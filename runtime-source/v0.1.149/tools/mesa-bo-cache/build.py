#!/usr/bin/env python3
"""Reproducible, exact-hash ARM64 instruction transform for the shipped Mesa.

This is NOT a full Mesa source rebuild. The accompanying source diff states the
equivalent cache predicate. Four instructions preserve layout/ABI/GOT profiles;
any GNU build ID is renewed (the pinned ELF has none). Unknown inputs are rejected.
"""
import argparse, hashlib, json, struct
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BEFORE = '73a212ba59c5fe3f13a932a685750f7601941a3c708a95569eea573b9106b7de'
REVISION = 'mesa-zink-bocache1'
# Final Vulkan chain -> x12, priority plus zero padding -> d1, original caller
# chain OR allocation flags -> x24 (cache predicate), final chain -> mai.pNext.
EDITS = [(0xc5e054, '1811899a', '0c11899a'), (0xc5e06c, 'e13300bd', 'e11b00fd'),
         (0xc5e070, 'ff3700b9', 'd80005aa'), (0xc5e074, 'f88700a9', 'ec8700a9')]
def sha(data): return hashlib.sha256(data).hexdigest()
def transform(data):
    assert sha(data) == BEFORE
    result=bytearray(data)
    phoff=struct.unpack_from('<Q',data,32)[0]; size,count=struct.unpack_from('<HH',data,54)
    segments=[struct.unpack_from('<IIQQQQQQ',data,phoff+i*size) for i in range(count)]
    edits=[]
    for va,old,new in EDITS:
        s=next(s for s in segments if s[0]==1 and s[1]&1 and s[3]<=va and va+4<=s[3]+s[5])
        offset=s[2]+va-s[3]; assert data[offset:offset+4].hex()==old
        result[offset:offset+4]=bytes.fromhex(new)
        edits.append(dict(va=va,offset=offset,before=old,after=new))
    notes=[]
    for s in segments:
        if s[0]!=4:continue
        off,end=s[2],s[2]+s[5]
        while off+12<=end:
            namesz,descsz,kind=struct.unpack_from('<III',data,off)
            name=data[off+12:off+12+namesz];desc=off+12+((namesz+3)&~3)
            if name==b'GNU\0' and kind==3:
                assert descsz==20
                old=data[desc:desc+descsz]
                new=hashlib.sha1((BEFORE+REVISION).encode()).digest()
                result[desc:desc+descsz]=new
                notes.append(dict(offset=desc,before=old.hex(),after=new.hex()))
            off=desc+((descsz+3)&~3)
    assert not notes, 'Pinned baseline has Android metadata but no GNU build ID'
    record=dict(revision=REVISION,method='EXACT_ARM64_INSTRUCTION_TRANSFORM',
        originalSha256=BEFORE,sha256=sha(result),bytes=len(result),edits=edits,buildId=None,
        builderSha256=sha(Path(__file__).read_bytes()),sourcePatchSha256=sha((HERE/'bo-cache.patch').read_bytes()),
        sourceReference='Pipetto-crypto/mesa@ccacc8abc043de9766f4341079d06bac5f3a1824',deviceTested=False)
    return bytes(result),record
def baseline(data,record):
    assert sha(data)==record['sha256']
    result=bytearray(data)
    for edit in record['edits']+([record['buildId']] if record['buildId'] else []):
        off=edit['offset'];after=bytes.fromhex(edit['after']);before=bytes.fromhex(edit['before'])
        assert result[off:off+len(after)]==after
        result[off:off+len(before)]=before
    assert sha(result)==BEFORE
    return bytes(result)
def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path);p.add_argument('--check',action='store_true');args=p.parse_args()
    native=ROOT/'engine/src/main/jniLibs/arm64-v8a'
    if args.check:
        record=json.loads((HERE/'build.json').read_text())
        data=(native/('libaura_'+record['sha256'][:24]+'.so')).read_bytes()
        updated,expected=transform(baseline(data,record))
        assert expected==record and updated==data
        print('Mesa BO cache transform and source contract verified');return
    original=(native/('libaura_'+BEFORE[:24]+'.so')).read_bytes()
    data,record=transform(original)
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_bytes(data)
    (HERE/'build.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps(record,indent=2))
if __name__=='__main__':main()
