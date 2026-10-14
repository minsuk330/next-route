"""Measured stop-pair hops (Seoul, weekday 09-16 median) + formula for the rest. Compare to ODsay."""
import csv, json, statistics as st, sys
from fit2 import prepare
from common import summ
MH = {}
for r in csv.DictReader(open('measured_hops_weekday.csv')):
    if r['band'] == '09-16' and int(r['count']) >= 20: MH[(r['a'], r['b'])] = float(r['median'])
SEB = (0.1191, 52.6)
P = [p for p in prepare() if 'hopstops' in p]
cov = []
for p in P:
    stops = p['hopstops'] + [None]
    # need next stop of each hop: rebuild from pairs_trips? hopstops holds hop start stops; end = next start (last hop end unknown)
    p['pairs'] = list(zip(p['hopstops'], p['hopstops'][1:]))
def leg(p, alpha, beta, use_meas=True):
    tot = 0.0; n_cov = 0
    hs = p['hopstops']; ds = p['hopd']
    for k in range(len(hs)):
        a = hs[k]; b = hs[k+1] if k+1 < len(hs) else p.get('alight')
        m = MH.get((a, b)) if (use_meas and b) else None
        if m is not None: tot += alpha*m; n_cov += 1
        else: tot += beta*(SEB[0]*ds[k] + SEB[1]) + 10
    return tot/60, n_cov/len(hs)
