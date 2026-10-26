"""Tiny SigV4 client for RustFS (no dependencies): list or delete objects in the bucket.
   Needs RUSTFS_ACCESS_KEY and RUSTFS_SECRET_KEY in the environment (set -a; . ./.env; set +a).
   Usage: python3 -I tests/e2e/s3tool.py list [prefix] | delete <key>..."""
import datetime, hashlib, hmac, os, sys, urllib.parse, urllib.request, urllib.error, xml.etree.ElementTree as ET
EP="http://127.0.0.1:9000"; BUCKET="adera-media"; AK=os.environ["RUSTFS_ACCESS_KEY"]; SK=os.environ["RUSTFS_SECRET_KEY"]; REGION="us-east-1"
def sign(method, path, query=""):
    t=datetime.datetime.now(datetime.timezone.utc); amz=t.strftime("%Y%m%dT%H%M%SZ"); day=t.strftime("%Y%m%d")
    host=urllib.parse.urlparse(EP).netloc; payload=hashlib.sha256(b"").hexdigest()
    canon_h=f"host:{host}\nx-amz-content-sha256:{payload}\nx-amz-date:{amz}\n"; signed="host;x-amz-content-sha256;x-amz-date"
    canon="\n".join([method,urllib.parse.quote(path),query,canon_h,signed,payload])
    scope=f"{day}/{REGION}/s3/aws4_request"
    to_sign="\n".join(["AWS4-HMAC-SHA256",amz,scope,hashlib.sha256(canon.encode()).hexdigest()])
    k=("AWS4"+SK).encode()
    for part in (day,REGION,"s3","aws4_request"): k=hmac.new(k,part.encode(),hashlib.sha256).digest()
    sig=hmac.new(k,to_sign.encode(),hashlib.sha256).hexdigest()
    return {"x-amz-date":amz,"x-amz-content-sha256":payload,"Authorization":f"AWS4-HMAC-SHA256 Credential={AK}/{scope}, SignedHeaders={signed}, Signature={sig}"}
def call(method, path, query=""):
    url=EP+urllib.parse.quote(path)+("?"+query if query else "")
    try: r=urllib.request.urlopen(urllib.request.Request(url,method=method,headers=sign(method,path,query)),timeout=20); return r.status,r.read()
    except urllib.error.HTTPError as e: return e.code,e.read()
def keys(prefix=""):
    q=urllib.parse.urlencode(sorted({"list-type":"2","prefix":prefix}.items()),quote_via=urllib.parse.quote)
    st,body=call("GET","/"+BUCKET,q)
    ns={"s":"http://s3.amazonaws.com/doc/2006-03-01/"}
    return [c.find("s:Key",ns).text for c in ET.fromstring(body).findall("s:Contents",ns)]
if __name__=="__main__":
    cmd=sys.argv[1]
    if cmd=="list": ks=keys(sys.argv[2] if len(sys.argv)>2 else ""); print(len(ks),"objects"); [print(" ",k) for k in ks]
    if cmd=="delete": 
        for k in sys.argv[2:]: print(" delete",k,call("DELETE","/"+BUCKET+"/"+k)[0])
