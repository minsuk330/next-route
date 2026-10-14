"""usage: otpq2.py olat olon dlat dlon when [window]  -> prints GraphQL body (BUS only, first 20)"""
import json,sys
a=sys.argv[1:]
olat,olon,dlat,dlon=map(float,a[:4]); when=a[4]
win=a[5] if len(a)>5 else "PT60M"
Q='''query($o:PlanLabeledLocationInput!,$d:PlanLabeledLocationInput!,$w:OffsetDateTime!,$m:PlanModesInput,$sw:Duration){planConnection(origin:$o,destination:$d,dateTime:{earliestDeparture:$w},first:20,searchWindow:$sw,modes:$m){
 searchDateTime routingErrors{code} edges{node{start end duration legs{mode duration distance headsign route{shortName longName type gtfsId agency{name}} trip{gtfsId}
 from{name lat lon stop{gtfsId code}} to{name lat lon stop{gtfsId code}} start{scheduledTime} end{scheduledTime} intermediatePlaces{name}}}}}}'''
v={"o":{"location":{"coordinate":{"latitude":olat,"longitude":olon}}},"d":{"location":{"coordinate":{"latitude":dlat,"longitude":dlon}}},"w":when,
   "m":{"transit":{"transit":[{"mode":"BUS","cost":{"reluctance":1.3}}]}},"sw":win}
print(json.dumps({"query":Q,"variables":v}))
