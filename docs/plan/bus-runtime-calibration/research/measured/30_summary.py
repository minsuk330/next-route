"""Summaries of out/segments.parquet. Usage: python -I 30_summary.py <work_dir>"""
import sys, os
import numpy as np, pandas as pd
pd.set_option('display.width', 220); pd.set_option('display.max_rows', 400); pd.set_option('display.max_columns', 30)
W = sys.argv[1]
seg = pd.read_parquet(os.path.join(W, 'out/segments.parquet'))
seg = seg[~seg.outlier]

def q(s):
    return pd.Series({'n': len(s), 'p25': s.quantile(.25), 'med': s.median(), 'p75': s.quantile(.75)})

def table(df, by):
    t = df.groupby(by).ratio.apply(q).unstack()
    t['n'] = t['n'].astype(int)
    return t.round(3)

wk = seg[seg.daytype == 'weekday']
for kind in ['SHORT', 'LONG', 'FULL']:
    s = seg[seg.kind == kind]; w = wk[wk.kind == kind]
    print(f'\n===== {kind}: all days / weekday ratio (measured/GTFS) =====')
    print('ALL     ', q(s.ratio).round(3).to_dict()); print('WEEKDAY ', q(w.ratio).round(3).to_dict())
    print('\n-- by daytype x band (pooled segments)'); print(table(s, ['daytype', 'band']))
    print('\n-- weekday by route type (pooled)'); print(table(w, ['type_name']))
    # route-level medians -> distribution across routes (each route weighted equally)
    rm = w.groupby(['route_id', 'route_name', 'type_name']).ratio.agg(['size', 'median']).reset_index()
    rm = rm[rm['size'] >= 30]
    print(f'\n-- weekday route-median distribution (routes with >=30 seg): n_routes={len(rm)}')
    print(rm.groupby('type_name')['median'].describe(percentiles=[.25, .5, .75]).round(3))
    print('\n-- weekday by period'); print(table(w, ['period']))
    print('\n-- weekday band x route type (pooled median)')
    print(w.pivot_table(index='type_name', columns='band', values='ratio', aggfunc='median').round(3))

# per-route table weekday (LONG + FULL)
print('\n===== per-route weekday: LONG median [p25,p75] / FULL median, band medians (LONG) =====')
lw = wk[wk.kind == 'LONG']; fw = wk[wk.kind == 'FULL']
pr = lw.groupby(['route_id', 'route_name', 'type_name', 'period']).ratio.agg(
    n='size', p25=lambda x: x.quantile(.25), med='median', p75=lambda x: x.quantile(.75))
pr['full_n'] = fw.groupby(['route_id', 'route_name', 'type_name', 'period']).size()
pr['full_med'] = fw.groupby(['route_id', 'route_name', 'type_name', 'period']).ratio.median()
pr['full_meas_min'] = fw.groupby(['route_id', 'route_name', 'type_name', 'period']).meas_s.median() / 60
pr['full_gtfs_min'] = fw.groupby(['route_id', 'route_name', 'type_name', 'period']).gtfs_s.median() / 60
bands = lw.pivot_table(index=['route_id', 'route_name', 'type_name', 'period'], columns='band', values='ratio', aggfunc='median')
pr = pr.join(bands[[c for c in ['06-09', '09-16', '16-20', '20-24'] if c in bands]])
pr = pr.round(3).reset_index().sort_values(['type_name', 'med'])
pr.to_csv(os.path.join(W, 'out/per_route_weekday.csv'), index=False)
print(pr.to_string(index=False))

# sensitivity: segment length (LONG nstop) and GTFS trip matching gap
print('\n-- weekday LONG ratio by GTFS match gap (min)')
print(table(lw.assign(gap=pd.cut(lw.gtfs_match_gap_s / 60, [-1, 5, 10, 20, 60, 1e9])), ['gap']))

# calibration potential: relative error |gtfs*f - meas|/meas, weekday, in-sample (optimistic for per-route factors)
print('\n===== calibration potential (weekday, median |pred-meas|/meas) =====')
for kind in ['SHORT', 'LONG']:
    w = wk[wk.kind == kind].copy()
    g = w.ratio.median()
    tb = w.groupby(['type_name', 'band']).ratio.transform('median')
    rb = w.groupby(['route_id', 'band']).ratio.transform('median')
    err = lambda f: ((w.gtfs_s * f - w.meas_s).abs() / w.meas_s).median()
    print(kind, f'raw GTFS={err(1.0):.3f}', f'global x{g:.2f}={err(g):.3f}', f'type*band={err(tb):.3f}', f'route*band={err(rb):.3f}')
    # absolute minutes underestimate of raw GTFS
    print('   meas-gtfs minutes p25/med/p75:', ((w.meas_s - w.gtfs_s) / 60).quantile([.25, .5, .75]).round(1).tolist(),
          ' meas min med:', round(w.meas_s.median() / 60, 1), ' gtfs min med:', round(w.gtfs_s.median() / 60, 1))
