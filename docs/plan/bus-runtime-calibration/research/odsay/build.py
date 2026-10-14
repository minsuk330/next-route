import json,os,glob,statistics as st,collections,csv
from analyze import odsay_segs,otp_groups,match,ODS,TYPE
REG={l.split()[0]:l.split()[1] for l in open('ods.txt')}
def key(x): return (x['od'],x['busNo'],x['start'],x['end'])
rows={}
for hour in ['14','08','18']:
    for od in ODS:
        if not os.path.exists(f'raw_odsay/{od}.json'): continue
        segs=odsay_segs(od)
        if not segs: continue
        for x in match(segs,otp_groups(f'{od}_{hour}')):
            r=rows.setdefault(key(x),dict(x,src='od',otp={}))
            r['otp'][hour]=x['otp_min']
# segment-level (14h only)
for f in sorted(glob.glob('raw_otp/*_seg*_14.json')):
    tag=os.path.basename(f)[:-8]; od=tag.split('_')[0]
    for x in match(odsay_segs(od),otp_groups(tag[:]+'_14')):
        if key(x) in rows: continue
        rows[key(x)]=dict(x,src='seg',otp={'14':x['otp_min']})
out=[]
for r in rows.values():
    if '14' not in r['otp']: 
        # matched only at 08/18 in OD-level; keep but use available
        pass
    bus=r['busNo']
    night = bus.upper().startswith('N') or '심야' in bus or '새벽' in bus
    dr=r['otp_dist']/r['dist']
    ok = (0.85<=dr<=1.15) and abs(r['nst']-(r['otp_nint']+1))<=2 and not night
    out.append(dict(od=r['od'],region=REG[r['od']],busNo=bus,type=TYPE.get(r['btype'],str(r['btype'])),start=r['start'],end=r['end'],
        odsay_min=r['t'],odsay_km=round(r['dist']/1000,2),odsay_stops=r['nst'],otp_km=round(r['otp_dist']/1000,2),otp_stops=r['otp_nint']+1,
        otp14=r['otp'].get('14'),otp08=r['otp'].get('08'),otp18=r['otp'].get('18'),dstart_m=r['dstart'],dend_m=r['dend'],src=r['src'],quality_ok=ok,night=night))
json.dump(out,open('pairs.json','w'),ensure_ascii=False,indent=0)
with open('pairs.csv','w',newline='') as f:
    w=csv.DictWriter(f,fieldnames=list(out[0].keys())); w.writeheader(); w.writerows(out)
print('pairs',len(out),'ok',sum(x['quality_ok'] for x in out),'with14',sum(1 for x in out if x['otp14'] is not None), 'src seg',sum(1 for x in out if x['src']=='seg'))
