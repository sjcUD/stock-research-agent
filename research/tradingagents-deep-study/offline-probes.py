"""Focused standard-library probes of audited source; not the upstream test suite."""
import ast
from pathlib import Path
import json
import logging

ROOT=Path('/Users/sjc/Documents/stock/reference-repos/TradingAgents')
def selected(rel,names,namespace):
 tree=ast.parse((ROOT/rel).read_text())
 nodes=[n for n in tree.body if isinstance(n,(ast.FunctionDef,ast.ClassDef)) and n.name in names]
 assert len(nodes)==len(names),(rel,names)
 exec(compile(ast.Module(body=nodes,type_ignores=[]),str(ROOT/rel),'exec'),namespace)
 return namespace

results=[]
def check(name,fn):
 fn()
 results.append({'name':name,'passed':True})
def equal(actual,expected):
 assert actual==expected,(actual,expected)
def raises(fn,kind):
 try:fn()
 except kind:return
 raise AssertionError('expected '+kind.__name__)

flow=selected('tradingagents/graph/conditional_logic.py',{'ConditionalLogic'},{'AgentState':dict})['ConditionalLogic']
check('bull response routes to bear',lambda:equal(flow().should_continue_debate({'investment_debate_state':{'count':1,'current_response':'Bull: evidence'}}),'Bear Researcher'))
check('one debate round means two contributions',lambda:equal(flow().should_continue_debate({'investment_debate_state':{'count':2,'current_response':'Bear: counterargument'}}),'Research Manager'))
check('one risk round means three contributions',lambda:equal(flow().should_continue_risk_analysis({'risk_debate_state':{'count':3,'latest_speaker':'Neutral'}}),'Portfolio Manager'))
check('aggressive routes to conservative',lambda:equal(flow().should_continue_risk_analysis({'risk_debate_state':{'count':1,'latest_speaker':'Aggressive Analyst'}}),'Conservative Analyst'))

ns={};exec(compile((ROOT/'tradingagents/dataflows/date_window.py').read_text(),'date_window.py','exec'),ns)
check('future model date clamped to research date',lambda:equal(ns['as_of']('2026-10-31','2025-01-10'),'2025-01-10'))
check('non padded date is normalized',lambda:equal(ns['as_of']('2025-1-3','2025-01-10'),'2025-01-03'))
check('future window shifts backwards preserving span',lambda:equal(ns['as_of_window']('2025-02-01','2025-02-08','2025-01-10'),('2025-01-03','2025-01-10')))
check('invalid window does not silently swap dates',lambda:raises(lambda:ns['as_of_window']('2025-02-08','2025-02-01','2025-01-10'),ValueError))
check('historical undated financials withheld',lambda:equal('withheld' in ns['withhold_undated_statements']('2025-01-10','AAPL','Income statement'),True))
check('historical undated news excluded',lambda:equal(ns['in_window'](None,ns['datetime'](2025,1,1),ns['datetime'](2025,1,10)),False))

tree=ast.parse((ROOT/'tradingagents/agents/schemas.py').read_text())
constant=next(n for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='_NULLISH_FLOAT' for t in n.targets))
ns={'_NULLISH_FLOAT':ast.literal_eval(constant.value)}
coerce=selected('tradingagents/agents/schemas.py',{'_coerce_optional_float'},ns)['_coerce_optional_float']
check('percentage is not an absolute price',lambda:equal(coerce('15%'),None))
check('formatted numeric price normalized',lambda:equal(coerce('$1,234.50'),1234.5))
check('price range dropped instead of hallucinated level',lambda:equal(coerce('150-160'),None))

ns={'logger':logging.getLogger('probe'),'OPTIONAL_CATEGORIES':{'macro_data','prediction_markets'}}
ns['logger'].addHandler(logging.NullHandler());ns['logger'].propagate=False
exec(compile((ROOT/'tradingagents/dataflows/errors.py').read_text(),'errors.py','exec'),ns)
ns['get_config']=lambda:{'data_vendors':{'prices':'a,b'}}
ns['TOOLS_CATEGORIES']={'prices':{'tools':['get_stock_data']}}
selected('tradingagents/dataflows/router.py',{'get_category_for_method','get_vendor','vendor_unavailable','no_data_available','route_to_vendor'},ns)
calls=[]
def impl(label,result=None,error=None):
 def run(*args,**kwargs):
  calls.append(label)
  if error:raise error
  return result
 return run
def scenario(config,vendors):
 calls.clear();ns['get_config']=lambda:config;ns['VENDOR_METHODS']={'get_stock_data':vendors}
 return ns['route_to_vendor']('get_stock_data','AAPL')
def explicit_only():
 equal(scenario({'data_vendors':{'prices':'a'}},{'a':impl('a',error=ns['VendorUnavailableError']('429')),'b':impl('b','secret fallback')} ).startswith('DATA_UNAVAILABLE:'),True)
 equal(calls,['a'])
check('only explicitly configured vendors are called',explicit_only)
def ordered_fallback():
 equal(scenario({'data_vendors':{'prices':'a,b'}},{'a':impl('a',error=ns['VendorUnavailableError']('429')),'b':impl('b','ok')}),'ok');equal(calls,['a','b'])
check('explicit ordered fallback works',ordered_fallback)
check('all clean no data produces no data sentinel',lambda:equal(scenario({'data_vendors':{'prices':'a'}},{'a':impl('a',error=ns['NoMarketDataError']('AAPL'))}).startswith('NO_DATA_AVAILABLE:'),True))
check('unavailable plus no data is not invalid instrument verdict',lambda:equal(scenario({'data_vendors':{'prices':'a,b'}},{'a':impl('a',error=ns['VendorUnavailableError']('429')),'b':impl('b',error=ns['NoMarketDataError']('AAPL'))}).startswith('DATA_UNAVAILABLE:'),True))
check('untyped core failure remains an error',lambda:raises(lambda:scenario({'data_vendors':{'prices':'a'}},{'a':impl('a',error=RuntimeError('failure'))}),RuntimeError))
check('tool configuration overrides category configuration',lambda:equal(scenario({'data_vendors':{'prices':'a'},'tool_vendors':{'get_stock_data':'b'}},{'a':impl('a','wrong'),'b':impl('b','right')}),'right'))

report={'commit':'c4544913a14ed3df5f1ed7b9e8483270ce20682a','scope':'Selected unmodified function/class definitions loaded from audited source via AST; no dependency installation, no network, no model calls; not the upstream pytest suite or a full graph run.','passed':len(results),'failed':0,'cases':results}
Path('/tmp/ta-offline-probes.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'passed':len(results),'failed':0,'report':'/tmp/ta-offline-probes.json'}))
