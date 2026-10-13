import json,statistics as st,collections
P=json.load(open('pairs.json'))
def q(v):
    v=sorted(v)
    if len(v)<2: return (len(v), v[0] if v else None, None, None)
    qs=st.quantiles(v,n=4,method='inclusive')
    return (len(v), round(st.median(v),2), round(qs[0],2), round(qs[2],2))
main=[p for p in P if p['quality_ok'] and p['otp14'] is not None]
print('MAIN pairs',len(main),'ODs',len({p['od'] for p in main}))
r=lambda p:p['otp14']/p['odsay_min']
print('ALL ratio n,med,p25,p75',q([r(p) for p in main]))
lg=[p for p in main if p['odsay_km']>=2]
print('>=2km',q([r(p) for p in lg]))
# unique segments (od,start,end) median of ratio
seg=collections.defaultdict(list)
for p in lg: seg[(p['od'],p['start'],p['end'])].append(r(p))
print('unique segs >=2km',q([st.median(v) for v in seg.values()]))
# minute diff
print('ODsay-OTP minutes (>=2km)',q([p['odsay_min']-p['otp14'] for p in lg]))
print('\nby region (>=2km): n med p25 p75 | unique segs')
for reg in ['서울','경기','인천','부산']:
    v=[p for p in lg if p['region']==reg]
    sg=collections.defaultdict(list)
    for p in v: sg[(p['od'],p['start'],p['end'])].append(r(p))
    print(reg,q([r(p) for p in v]),'| segs',q([st.median(x) for x in sg.values()]))
print('\nby type (>=2km)')
for t,c in collections.Counter(p['type'] for p in lg).most_common():
    v=[p for p in lg if p['type']==t]
    print(t,q([r(p) for p in v]), sorted({p['region'] for p in v}))
print('\nby region x type (>=2km)')
for (reg,t),c in sorted(collections.Counter((p['region'],p['type']) for p in lg).items()):
    v=[p for p in lg if p['region']==reg and p['type']==t]
    print(reg,t,q([r(p) for p in v]))
print('\nby distance (all main)')
for lo,hi in [(0,2),(2,4),(4,6),(6,10),(10,40)]:
    v=[p for p in main if lo<=p['odsay_km']<hi]
    print(f'{lo}-{hi}km',q([r(p) for p in v]))
print('\nby distance excl Busan')
for lo,hi in [(0,2),(2,4),(4,6),(6,10),(10,40)]:
    v=[p for p in main if lo<=p['odsay_km']<hi and p['region']!='부산']
    print(f'{lo}-{hi}km',q([r(p) for p in v]))
# speed
print('\nspeeds km/h (>=2km): ODsay vs OTP medians by region')
for reg in ['서울','경기','인천','부산']:
    v=[p for p in lg if p['region']==reg]
    print(reg, round(st.median([p['odsay_km']/p['odsay_min']*60 for p in v]),1), round(st.median([p['otp_km']/p['otp14']*60 for p in v if p['otp14']>0]),1))
# linear fit ODsay min vs km, OTP min vs km (excl busan)
def fit(xs,ys):
    mx,my=st.mean(xs),st.mean(ys); b=sum((x-mx)*(y-my) for x,y in zip(xs,ys))/sum((x-mx)**2 for x in xs); return round(my-b*mx,2),round(b,2)
for reg in ['서울','경기','인천','부산']:
    v=[p for p in main if p['region']==reg]
    print(reg,'fit min=a+b*km ODsay',fit([p['odsay_km'] for p in v],[p['odsay_min'] for p in v]),'OTP',fit([p['otp_km'] for p in v],[p['otp14'] for p in v]))
# fit per stop
for reg in ['서울','경기','인천','부산']:
    v=[p for p in main if p['region']==reg]
    print(reg,'min per stop ODsay',round(st.median([p['odsay_min']/p['odsay_stops'] for p in v]),2),'OTP',round(st.median([p['otp14']/p['otp_stops'] for p in v]),2))
# time of day
print('\ntime of day (pairs with all 3 hours, quality ok)')
t3=[p for p in P if p['quality_ok'] and all(p[k] is not None for k in ('otp08','otp14','otp18'))]
for k in ('otp08','otp14','otp18'):
    print(k,q([p[k]/p['odsay_min'] for p in t3]))
print('n',len(t3),'08/14',q([p['otp08']/p['otp14'] for p in t3]),'18/14',q([p['otp18']/p['otp14'] for p in t3]))
for reg in ['서울','경기','인천','부산']:
    v=[p for p in t3 if p['region']==reg]
    if v: print(reg,len(v),'08/14',q([p['otp08']/p['otp14'] for p in v])[1:],'18/14',q([p['otp18']/p['otp14'] for p in v])[1:])
print('\nOUTLIERS')
for p in sorted(main,key=r)[:8]+sorted(main,key=r)[-6:]:
    print(round(r(p),2),p['region'],p['busNo'],p['type'],p['start'],'->',p['end'],p['odsay_min'],'vs',p['otp14'],p['odsay_km'],'km',p['odsay_stops'],'stops')
