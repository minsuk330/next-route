import json,math,glob,re,os,sys,statistics as st
TYPE={1:'일반',2:'좌석',3:'마을',4:'직행좌석',5:'공항',6:'간선급행',10:'외곽',11:'간선',12:'지선',13:'순환',14:'광역',15:'급행',16:'관광',20:'농어촌',22:'경기시외형',26:'급행간선'}
def hav(a,b,c,d):
    R=6371000;p1,p2=math.radians(a),math.radians(c);dp=p2-p1;dl=math.radians(d-b)
    h=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(h))
def norm(s): return re.sub(r'[\s\-]','',re.sub(r'\(.*?\)','',s or '')).upper()
ODS={l.split()[0]:l.split() for l in open('ods.txt') if l.strip()}
def odsay_segs(od):
    r=json.load(open(f'raw_odsay/{od}.json'))
    if 'result' not in r: return []
    out={}
    for pi,p in enumerate(r['result']['path']):
        bus_only = p['pathType']==2
        nbus=sum(1 for s in p['subPath'] if s['trafficType']==2)
        for s in p['subPath']:
            if s['trafficType']!=2: continue
            for ln in s['lane']:
                k=(ln['busNo'],s['startID'],s['endID'])
                if k in out: continue
                out[k]=dict(od=od,busNo=ln['busNo'],btype=ln.get('type'),start=s['startName'],end=s['endName'],
                    sy=s['startY'],sx=s['startX'],ey=s['endY'],ex=s['endX'],t=s['sectionTime'],dist=s['distance'],
                    nst=s['stationCount'],pidx=pi,direct=(bus_only and nbus==1),nlanes=len(s['lane']))
    return list(out.values())
def otp_groups(tag):
    f=f'raw_otp/{tag}.json'
    if not os.path.exists(f): return None
    r=json.load(open(f))
    if 'errors' in r or not r.get('data'): return None
    g={}
    for e in r['data']['planConnection']['edges']:
        for l in e['node']['legs']:
            if l['mode']!='BUS' or not l['route']: continue
            k=(l['route']['shortName'],l['from']['stop']['gtfsId'] if l['from'].get('stop') else l['from']['name'],l['to']['stop']['gtfsId'] if l['to'].get('stop') else l['to']['name'])
            d=g.setdefault(k,dict(route=l['route']['shortName'],fn=l['from']['name'],tn=l['to']['name'],fy=l['from']['lat'],fx=l['from']['lon'],ty=l['to']['lat'],tx=l['to']['lon'],durs=[],dist=l['distance'],nint=len(l['intermediatePlaces'] or []),deps=[]))
            d['durs'].append(l['duration']/60); d['deps'].append(l['start']['scheduledTime'][11:16])
    return list(g.values())
def match(segs,groups,tol=300):
    res=[]
    for s in segs:
        best=None
        for g in groups or []:
            if norm(g['route'])!=norm(s['busNo']): continue
            d1=hav(s['sy'],s['sx'],g['fy'],g['fx']); d2=hav(s['ey'],s['ex'],g['ty'],g['tx'])
            if d1<=tol and d2<=tol and (best is None or d1+d2<best[0]): best=(d1+d2,d1,d2,g)
        if best:
            _,d1,d2,g=best
            res.append(dict(s, otp_min=st.median(g['durs']), otp_n=len(g['durs']), otp_dist=g['dist'], otp_from=g['fn'], otp_to=g['tn'], otp_nint=g['nint'], dstart=round(d1), dend=round(d2), deps=g['deps']))
    return res
if __name__=='__main__':
    hour=sys.argv[1] if len(sys.argv)>1 else '14'
    allm=[];unm=[]
    for od in ODS:
        if not os.path.exists(f'raw_odsay/{od}.json'): continue
        segs=odsay_segs(od)
        if not segs: continue
        g=otp_groups(f'{od}_{hour}')
        m=match(segs,g)
        allm+=m
        mk={(x['busNo'],x['start'],x['end']) for x in m}
        unm+=[s for s in segs if (s['busNo'],s['start'],s['end']) not in mk]
        print(od, 'odsay segs',len(segs),'otp groups',len(g or []),'matched',len(m))
    json.dump(allm,open(f'matched_od_{hour}.json','w'),ensure_ascii=False,indent=0)
    json.dump(unm,open(f'unmatched_od_{hour}.json','w'),ensure_ascii=False,indent=0)
    print('total matched',len(allm))
