import struct, sys, json

def read_pcap(path):
    with open(path,'rb') as f: buf=f.read()
    magic=buf[:4]
    if magic in (b'\xd4\xc3\xb2\xa1', b'\xa1\xb2\xc3\xd4'):
        endian='<' if magic==b'\xd4\xc3\xb2\xa1' else '>'; nano=1000000
    elif magic in (b'\x4d\x3c\xb2\xa1', b'\xa1\xb2\x3c\x4d'):
        endian='<' if magic==b'\x4d\x3c\xb2\xa1' else '>'; nano=1000000000
    else:
        raise SystemExit('not pcap: %r' % magic)
    vmaj,vmin,tz,sig,snap,link=struct.unpack(endian+'HHiIII', buf[4:24])
    off=24; pkts=[]
    while off+16<=len(buf):
        ts,tu,incl,orig=struct.unpack(endian+'IIII', buf[off:off+16]); off+=16
        data=buf[off:off+incl]; off+=incl
        pkts.append((ts,tu,incl,orig,data))
    return dict(endian=endian,nano=nano,link=link,snap=snap,n=len(pkts),pkts=pkts,
                vmaj=vmaj,vmin=vmin)

def ip_hdr(data):
    # ethernet
    if len(data)<14: return None
    et=struct.unpack('>H',data[12:14])[0]; o=14
    if et==0x8100:
        et=struct.unpack('>H',data[16:18])[0]; o=18
    if et==0x0800: ver=4
    elif et==0x86DD: ver=6
    else: return None
    if ver==4:
        ihl=(data[o]&0x0f)*4
        proto=data[o+9]; tot=struct.unpack('>H',data[o+2:o+4])[0]
        src='.'.join(str(b) for b in data[o+12:o+16])
        dst='.'.join(str(b) for b in data[o+16:o+20])
        frag=struct.unpack('>H',data[o+6:o+8])[0]
        return dict(ver=4,proto=proto,src=src,dst=dst,off=o+ihl,ihl=ihl,
                    tot=tot,fragoff=(frag & 0x1fff),mf=bool(frag & 0x2000))
    else:
        nxt=data[o+6]; plen=struct.unpack('>H',data[o+4:o+6])[0]
        src=':'.join('%x'%struct.unpack('>H',data[o+8+i:o+10+i])[0] for i in range(0,16,2))
        dst=':'.join('%x'%struct.unpack('>H',data[o+24+i:o+26+i])[0] for i in range(0,16,2))
        return dict(ver=6,proto=nxt,src=src,dst=dst,off=o+40,ihl=40,tot=plen+40,
                    fragoff=0,mf=False)

def tcp_hdr(data,off):
    if len(data)<off+20: return None
    sp,dp,seq,ack,off_flags,win,cks,urg=struct.unpack('>HHIIHHHH',data[off:off+20])
    doff=(off_flags>>12)*4
    flags=off_flags&0x1ff
    fl=''.join(c for c,b in (('F',1),('S',2),('R',4),('P',8),('A',16),('U',32),('E',64),('C',128)) if flags&b)
    return dict(sp=sp,dp=dp,seq=seq,ack=ack,doff=doff,flags=fl,win=win,plen=None,
                payload=data[off+doff:])

def parse(path):
    P=read_pcap(path); out=[]
    for i,(ts,tu,incl,orig,data) in enumerate(P['pkts']):
        t=ts+tu/P['nano']
        ip=ip_hdr(data)
        rec=dict(i=i+1,t=t,ts=ts,tu=tu,wire=orig,cap=incl)
        if not ip: rec['kind']='nonip'; out.append(rec); continue
        rec.update(ip)
        if ip['proto'] not in (6,): rec['kind']='ip%d-proto%d'%(ip['ver'],ip['proto']); out.append(rec); continue
        tcp=tcp_hdr(data,ip['off'])
        if not tcp: rec['kind']='badtcp'; out.append(rec); continue
        rec.update(tcp); rec['plen']=len(tcp['payload'])
        rec['kind']='tcp'
        out.append(rec)
    return P,out

if __name__=='__main__':
    P,recs=parse(sys.argv[1])
    print(json.dumps(dict(link=P['link'],snap=P['snap'],n=P['n'],nano=P['nano'],
        ver=(P['vmaj'],P['vmin'])),indent=1))
