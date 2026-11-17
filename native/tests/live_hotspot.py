import os, fcntl, struct, socket, subprocess, sys, select
# Real-kernel check of the hotspot counting (root). From the repo root:
#   gcc -Wno-override-init -o /tmp/live_hotspot native/tests/live_hotspot.c native/jni/service/ipt.c -lpthread
#   unshare -n python3 native/tests/live_hotspot.py 200
TUNSETIFF=0x400454ca
def mktun(name):
    fd=os.open('/dev/net/tun',os.O_RDWR|os.O_NONBLOCK)
    fcntl.ioctl(fd,TUNSETIFF,struct.pack('16sH',name.encode(),0x0001|0x1000))  # IFF_TUN|IFF_NO_PI
    return fd
def ifcfg(name,addr,mask):
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    def sa(a): return struct.pack('H2s4s8s',socket.AF_INET,b'\0\0',socket.inet_aton(a),b'')
    fcntl.ioctl(s,0x8916,struct.pack('16s16s',name.encode(),sa(addr)))   # SIOCSIFADDR
    fcntl.ioctl(s,0x891c,struct.pack('16s16s',name.encode(),sa(mask)))   # SIOCSIFNETMASK
    fl=struct.unpack('16sH',fcntl.ioctl(s,0x8913,struct.pack('16sH',name.encode(),0)))[1]
    fcntl.ioctl(s,0x8914,struct.pack('16sH',name.encode(),fl|1))        # SIOCSIFFLAGS up
def csum(b):
    if len(b)%2: b+=b'\0'
    s=sum(struct.unpack('!%dH'%(len(b)//2),b)); s=(s>>16)+(s&0xffff); s+=s>>16; return ~s&0xffff
def pkt(src,dst,payload):
    udp=struct.pack('!HHHH',5000,6000,8+len(payload),0)+payload
    ip=struct.pack('!BBHHHBBH4s4s',0x45,0,20+len(udp),1,0,64,17,0,socket.inet_aton(src),socket.inet_aton(dst))
    ip=ip[:10]+struct.pack('!H',csum(ip))+ip[12:]
    return ip+udp
def stat(i,k): return int(open(f'/sys/class/net/{i}/statistics/{k}').read())
ap=mktun('ap0'); up=mktun('up0')
ifcfg('ap0','10.9.0.1','255.255.255.0'); ifcfg('up0','10.8.0.1','255.255.255.0')
open('/proc/sys/net/ipv4/ip_forward','w').write('1')
for i in ('all','default','ap0','up0'):
    try: open(f'/proc/sys/net/ipv4/conf/{i}/rp_filter','w').write('0')
    except Exception as e: print('rp_filter',i,e)
r=subprocess.run(['/tmp/live_hotspot','install'],capture_output=True,text=True); print(r.stdout.strip(),r.stderr.strip())
if r.returncode: sys.exit(1)
N=int(sys.argv[1]) if len(sys.argv)>1 else 200
up_pkt=pkt('10.9.0.2','10.8.0.2',b'x'*300); dn_pkt=pkt('10.8.0.2','10.9.0.2',b'y'*700)
def drain(fd):
    n=0
    while select.select([fd],[],[],0.05)[0]:
        try: n+=len(os.read(fd,65535))
        except BlockingIOError: break
    return n
for _ in range(N):
    os.write(ap,up_pkt)      # client -> phone through ap0
    os.write(up,dn_pkt)      # internet -> phone through up0, forwarded to the client on ap0
got_up=drain(up); got_dn=drain(ap)
print('forwarded out of up0 (client upload) bytes:',got_up,' expected',N*len(up_pkt))
print('forwarded out of ap0 (client download) bytes:',got_dn,' expected',N*len(dn_pkt))
out=subprocess.run(['/tmp/live_hotspot'],capture_output=True,text=True).stdout; print(out.strip())
pass
