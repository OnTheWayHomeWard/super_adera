"""End-to-end journey through the GATEWAY: sign up two users, Kratos webhook -> profile, profile/extra/location,
avatar upload to RustFS, FCM device tokens, spoofing attempts, logout. Needs the stack running in DEV mode
(SMS_PROVIDER=console, NOTIFY_DEV_ENDPOINTS=true) so the OTP can be read from the notification service.
Run from the repo root:  python3 -I tests/e2e/journey.py      (cleans up after itself)"""
import http.client, json, os, re, struct, subprocess, sys, time, urllib.parse, urllib.request, urllib.error, uuid, zlib
G="http://127.0.0.1:9080"; K=G+"/kratos"; ME=G+"/api/v1/me"; N="http://127.0.0.1:8085"; ADMIN="http://127.0.0.1:4434"

def req(method, url, body=None, headers=None, raw=None, ctype=None):
    h={"accept":"application/json",**(headers or {})}; data=raw
    if body is not None: data=json.dumps(body).encode(); h["content-type"]="application/json"
    if ctype: h["content-type"]=ctype
    try:
        r=urllib.request.urlopen(urllib.request.Request(url,data=data,method=method,headers=h),timeout=30); b=r.read(); c=r.status; hd=r.headers
    except urllib.error.HTTPError as e:
        try: b=e.read()
        except http.client.IncompleteRead as ie: b=ie.partial   # server closed the connection right after the status line
        c=e.code; hd=e.headers
    try: return c,json.loads(b or b"{}"),hd
    except Exception: return c,b,hd

def signup(phone):
    req("DELETE",N+"/dev/messages")
    _,f,_=req("GET",K+"/self-service/registration/api"); fid=f["id"]
    req("POST",f"{K}/self-service/registration?flow={fid}",{"method":"code","traits.phone_number":phone})
    code=None
    for _ in range(15):
        _,m,_=req("GET",f"{N}/dev/messages?to={urllib.parse.quote(phone)}")
        if isinstance(m,list) and m: code=re.search(r"\b(\d{6})\b",m[0]["body"]).group(1); break
        time.sleep(1)
    _,r,_=req("POST",f"{K}/self-service/registration?flow={fid}",{"method":"code","traits.phone_number":phone,"code":code})
    return r["session_token"], r["identity"]["id"]

def png(w,h,noise=False):
    rows=b"".join(b"\x00"+(os.urandom(w*3) if noise else bytes([200,30,30])*w) for _ in range(h))
    def chunk(t,d): c=struct.pack(">I",len(d))+t+d; return c+struct.pack(">I",zlib.crc32(t+d)&0xffffffff)
    return b"\x89PNG\r\n\x1a\n"+chunk(b"IHDR",struct.pack(">IIBBBBB",w,h,8,2,0,0,0))+chunk(b"IDAT",zlib.compress(rows))+chunk(b"IEND",b"")

def multipart(fieldname, filename, content, mime):
    b="----adera"+uuid.uuid4().hex
    body=(f"--{b}\r\nContent-Disposition: form-data; name=\"{fieldname}\"; filename=\"{filename}\"\r\nContent-Type: {mime}\r\n\r\n").encode()+content+f"\r\n--{b}--\r\n".encode()
    return body, f"multipart/form-data; boundary={b}"

def jpeg_size(b):
    i=2
    while i<len(b):
        if b[i]!=0xFF: i+=1; continue
        m=b[i+1]
        if m in (0xC0,0xC1,0xC2): return struct.unpack(">HH",b[i+5:i+9])[::-1]
        i+=2+struct.unpack(">H",b[i+2:i+4])[0]
def psql(sql): return subprocess.run(["docker","compose","exec","-T","postgres","psql","-U","adera_admin","-d","users","-Atc",sql],capture_output=True,text=True).stdout.strip()
def ok(label, cond, extra=""): print(("  PASS " if cond else "  FAIL ")+label+(f"  [{extra}]" if extra else "")); return cond

results=[]
phoneA="+251977"+str(uuid.uuid4().int)[:6]; phoneB="+251978"+str(uuid.uuid4().int)[:6]
print("== 1. sign up two users through the gateway")
tokA,idA=signup(phoneA); tokB,idB=signup(phoneB)
A={"Authorization":"Bearer "+tokA}; B={"Authorization":"Bearer "+tokB}
results.append(ok("both signed up with session tokens", bool(tokA and tokB)))

print("== 2. Kratos webhook created the profile (before any API call)")
time.sleep(3)
results.append(ok("app_user row exists from the webhook", psql(f"select count(*) from app_user where id='{idA}'")=="1"))
results.append(ok("phone copied from Kratos traits", psql(f"select phone_e164 from app_user where id='{idA}'")==phoneA))

print("== 3. profile through the gateway")
c,p,_=req("GET",ME,headers=A)
results.append(ok("GET /me returns the profile", c==200 and p["id"]==idA and p["phone"]==phoneA and p["market"]=="ET", f"{c}"))
c,p,_=req("PATCH",ME,{"firstName":"Selam","lastName":"Tadesse","gender":"female","dateOfBirth":"1995-04-12","language":"am","extra":{"occupation":"nurse","prefs":{"theme":"dark"}}},headers=A)
results.append(ok("PATCH sets fields + extra JSON", c==200 and p["firstName"]=="Selam" and p["gender"]=="FEMALE" and p["extra"]["prefs"]["theme"]=="dark", f"{c}"))
c,p,_=req("PATCH",ME,{"extra":{"occupation":None,"prefs":{"font":16}}},headers=A)
results.append(ok("extra merge: removed key, kept sibling, added nested", c==200 and "occupation" not in p["extra"] and p["extra"]["prefs"]=={"theme":"dark","font":16}))
c,p,_=req("PUT",ME+"/location",{"countryCode":"ET","region":"Addis Ababa","city":"Addis Ababa","latitude":9.0054,"longitude":38.7636},headers=A)
results.append(ok("location saved", c==200 and p["location"]["city"]=="Addis Ababa"))
c,p,_=req("PATCH",ME,{"gender":"robot"},headers=A)
results.append(ok("invalid value rejected with field message", c==400 and "gender" in p.get("fields",{}), f"{c}"))
print("   extra in database:", psql(f"select extra from app_user where id='{idA}'"))

print("== 4. avatar upload through the gateway -> RustFS")
body,ct=multipart("file","me.png",png(900,600),"image/png")
c,p,_=req("POST",ME+"/avatar",raw=body,ctype=ct,headers=A)
url=p.get("avatarUrl") if isinstance(p,dict) else None
results.append(ok("upload accepted, avatar id + signed url returned", c==200 and bool(url) and bool(p.get("avatarImageId")), f"{c}"))
if url:
    r=urllib.request.urlopen(url,timeout=20); img=r.read()
    results.append(ok("signed URL serves the image from RustFS", r.status==200 and img[:2]==b"\xff\xd8", f"{len(img)} bytes, {r.headers.get('Content-Type')}"))
    results.append(ok("stored as 512x512 JPEG (cropped + re-encoded)", jpeg_size(img)==(512,512), str(jpeg_size(img))))
    print("   url host:", urllib.parse.urlparse(url).netloc, "| key:", urllib.parse.urlparse(url).path)
    try: urllib.request.urlopen(url.split("?")[0],timeout=10); results.append(ok("unsigned URL is NOT public", False))
    except urllib.error.HTTPError as e: results.append(ok("unsigned URL is NOT public", e.code in (401,403), str(e.code)))
results.append(ok("avatar id stored in the database", psql(f"select avatar_image_id is not null from app_user where id='{idA}'")=="t"))
c,_,_=req("POST",ME+"/avatar",raw=multipart("file","x.png",b"<html>not an image</html>","image/png")[0],ctype=multipart("file","x.png",b"x","image/png")[1],headers=A)
results.append(ok("non-image rejected", c==400, str(c)))
big,ct=multipart("file","big.png",png(900,900,noise=True),"image/png")
c,p,_=req("POST",ME+"/avatar",raw=big,ctype=ct,headers=A)
results.append(ok(f"~{len(big)//1024//1024}MB upload passes the gateway and is handled", c in (200,413), f"{c} ({len(big)} bytes)"))
huge,ct=multipart("file","huge.bin",os.urandom(7*1024*1024),"image/png")
c,_,_=req("POST",ME+"/avatar",raw=huge,ctype=ct,headers=A)
results.append(ok("7MB upload rejected (413)", c==413, str(c)))

print("== 5. devices / FCM tokens")
tok1="fcm-"+uuid.uuid4().hex; tok2="fcm-"+uuid.uuid4().hex
dev=lambda t:{"fcmToken":t,"platform":"ANDROID","installationId":"inst-"+idA[:8],"appVersion":"1.0.3","deviceName":"Pixel 7"}
c,_,_=req("PUT",ME+"/devices",dev(tok1),headers=A); results.append(ok("device registered", c==200))
c,_,_=req("PUT",ME+"/devices",dev(tok2),headers=A)
c,l,_=req("GET",ME+"/devices",headers=A)
results.append(ok("rotated token replaced the old one (1 device)", c==200 and len(l)==1))
results.append(ok("DB holds only the new token", psql(f"select count(*) from device where user_id='{idA}'")=="1" and psql(f"select count(*) from device where fcm_token='{tok1}'")=="0"))

print("== 6. spoofing and isolation")
c,p,_=req("GET",ME,headers={**A,"X-User-Id":idB})
results.append(ok("forged X-User-Id is overridden by the gateway (still user A)", c==200 and p["id"]==idA, p.get("id","")[:8] if isinstance(p,dict) else ""))
c,p,_=req("GET",ME,headers=B); results.append(ok("user B sees only their own profile", p["id"]==idB and p["firstName"] is None))
c,_,_=req("GET",ME); results.append(ok("no token -> 401 at the gateway", c==401))
c,_,_=req("GET",ME,headers={"Authorization":"Bearer garbage"}); results.append(ok("garbage token -> 401", c==401))
c,_,_=req("GET","http://127.0.0.1:8086/api/v1/me",headers={"X-User-Id":idA}); results.append(ok("bypassing the gateway is rejected (no secret)", c==401))

req("DELETE",ME+"/avatar",headers=A)   # tidy: remove the test avatar so no object is left in storage
print("== 7. logout (push token + session)")
c,_,_=req("POST",ME+"/logout",{"fcmToken":tok2},headers=A)
results.append(ok("logout -> 204", c==204, str(c)))
results.append(ok("push token removed", psql(f"select count(*) from device where user_id='{idA}'")=="0"))
c,_,_=req("GET",ME,headers=A); results.append(ok("old session token now rejected", c==401, str(c)))
c,_,_=req("GET",K+"/sessions/whoami",headers=A); results.append(ok("Kratos whoami also 401", c==401))
c,p,_=req("GET",ME,headers=B); results.append(ok("user B (still signed in) unaffected", c==200))

print("== cleanup")
req("DELETE",ME+"/avatar",headers=B)
_,ids,_=req("GET",ADMIN+"/admin/identities")
for i in ids:
    if i["traits"].get("phone_number") in (phoneA,phoneB): req("DELETE",f"{ADMIN}/admin/identities/{i['id']}")
psql(f"delete from app_user where id in ('{idA}','{idB}')")
print("  removed test identities and rows; remaining app_user rows:", psql("select count(*) from app_user"))
print(f"\nRESULT: {sum(map(bool,results))}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
