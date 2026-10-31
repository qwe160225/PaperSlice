#!/usr/bin/env python3
"""Real QPDF core integration tests. Large fixtures are generated, not bundled."""
import pathlib, subprocess, time, json, resource, re, hashlib
import fitz
from reportlab.pdfgen import canvas
root = pathlib.Path(__file__).resolve().parents[1]
work = root/'test-work'; work.mkdir(exist_ok=True)
cli = root/'native-build/host/app/paperslice-cli'
qpdf = root/'native-build/host/app/qpdf/qpdf/qpdf'
checks = 0
results = []
def check(value, message):
    global checks
    assert value, message
    checks += 1
def cap(): resource.setrlimit(resource.RLIMIT_AS, (256*1024**2, 256*1024**2))
def run(args, success=True):
    start=time.monotonic()
    r=subprocess.run([str(x) for x in args], capture_output=True, text=True, preexec_fn=cap, timeout=180)
    check((r.returncode == 0) == success, r.stderr or r.stdout)
    return r, time.monotonic()-start
def shared_pdf(path, pages, width, height):
    offsets=[0]
    with path.open('wb') as f:
        f.write(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n')
        def obj(n, content):
            assert n == len(offsets)
            offsets.append(f.tell()); f.write(f'{n} 0 obj\n'.encode()); f.write(content); f.write(b'\nendobj\n')
        obj(1,b'<< /Type /Catalog /Pages 2 0 R >>')
        kids=' '.join(f'{4+i*3} 0 R' for i in range(pages))
        obj(2,f'<< /Type /Pages /Count {pages} /Kids [{kids}] /Resources 3 0 R >>'.encode())
        resources=' '.join(f'/Im{i+1} {6+i*3} 0 R' for i in range(pages))
        obj(3,f'<< /XObject << {resources} >> >>'.encode())
        for i in range(pages):
            n=4+i*3
            obj(n,f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {width} {height}] /Contents {n+1} 0 R >>'.encode())
            content=f'q {width} 0 0 {height} 0 0 cm /Im{i+1} Do Q\n'.encode()
            obj(n+1,f'<< /Length {len(content)} >>\nstream\n'.encode()+content+b'endstream')
            # Unique pixel patterns distinguish page order and preserve large stream size.
            pattern=bytes((v+i*17)%256 for v in range(256))
            data=(pattern*((width*height+255)//256))[:width*height]
            obj(n+2,f'<< /Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace /DeviceGray /BitsPerComponent 8 /Length {len(data)} >>\nstream\n'.encode()+data+b'\nendstream')
        xref=f.tell(); f.write(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
        for off in offsets[1:]: f.write(f'{off:010} 00000 n \n'.encode())
        f.write(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
def compare(source, output, pages):
    with fitz.open(source) as a, fitz.open(output) as b:
        check(len(b)==len(pages),'page count')
        for i,p in enumerate(pages):
            pa=a[p-1].get_pixmap(); pb=b[i].get_pixmap()
            check(pa.width==pb.width and pa.height==pb.height and pa.samples==pb.samples,f'render page {p}')

small=work/'small.pdf'
c=canvas.Canvas(str(small),pagesize=(300,400))
for n in range(1,9):
    c.drawString(30,350,f'PAGE {n} - PaperSlice QPDF')
    c.setFillColorRGB(n/10,0.3,0.5);c.rect(30,40,n*20,200,fill=1)
    c.linkURL('https://example.com', (30,330,240,360))
    c.showPage()
c.save()
out=work/'small-out.pdf'
run([cli,'extract',small,'1,3-5,8',out]);compare(small,out,[1,3,4,5,8])
with fitz.open(out) as doc:
    check('PAGE 3' in doc[1].get_text(),'searchable text')
    check(bool(doc[0].get_links()),'link annotation')
run([cli,'extract',small,'9',out],False)
damaged=work/'damaged.pdf'
damaged.write_bytes(re.sub(rb'startxref\n\d+',b'startxref\n1',small.read_bytes()))
run([cli,'count',damaged],False)
run([cli,'extract',damaged,'1-2',out,'','repair']);compare(small,out,[1,2])

encrypted=work/'encrypted.pdf'
run([qpdf,small,'--encrypt','user','owner','256','--modify=none','--',encrypted])
run([cli,'extract',encrypted,'1-2',out,'wrong'],False)
run([cli,'extract',encrypted,'1-2',out,'user'],False)
run([cli,'extract',encrypted,'1-2',out,'owner']);compare(small,out,[1,2])
unicode=work/'unicode.pdf'
run([qpdf,small,'--object-streams=generate','--encrypt','密码🔑','所有者🔐','256','--',unicode])
run([cli,'extract',unicode,'2,7',out,'密码🔑']);compare(small,out,[2,7])

for name,total,w,h,selection,pages in [
    ('300MB',500,1000,600,'1-40',list(range(1,41))),
    ('1GB',400,1000,2500,'1-10,101-110,201-210,397-400',list(range(1,11))+list(range(101,111))+list(range(201,211))+list(range(397,401)))]:
    src=work/(name+'.pdf');dst=work/(name+'-out.pdf')
    shared_pdf(src,total,w,h)
    r,counttime=run([cli,'count',src]);check(int(r.stdout)==total,'source page count')
    r,elapsed=run([cli,'extract',src,selection,dst])
    compare(src,dst,pages)
    with fitz.open(dst) as doc:
        images=sum(doc.xref_get_key(i,'Subtype')==('name','/Image') for i in range(1,doc.xref_length()))
    check(images==len(pages),'unused images removed')
    check(dst.stat().st_size<src.stat().st_size*0.15,'file shrinks')
    results.append(dict(sample=name,source_pages=total,selected_pages=len(pages),input_bytes=src.stat().st_size,
        output_bytes=dst.stat().st_size,retained_images=images,count_seconds=round(counttime,4),extract_seconds=round(elapsed,4)))
    print(json.dumps(results[-1]),flush=True)
    check(int(run([cli,'count',src])[0].stdout)==total,'source intact')

report=dict(checks_passed=checks,native_address_space_limit_mib=256,platform='Linux x86_64; NOT Android phone',results=results)
(root/'verification').mkdir(exist_ok=True)
(root/'verification/native-results.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report),flush=True)
