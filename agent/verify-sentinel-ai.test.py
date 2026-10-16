import contextlib, hashlib, importlib.util, io, json, sqlite3, tempfile, unittest
from pathlib import Path

spec=importlib.util.spec_from_file_location('verifier',Path(__file__).with_name('verify-sentinel-ai.py'))
verifier=importlib.util.module_from_spec(spec);spec.loader.exec_module(verifier)

class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='tripoder-ai-verifier-');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.db=self.root/'synthetic.db';self.run='control-synthetic'
        self.c=sqlite3.connect(self.db);self.addCleanup(self.c.close)
        self.c.executescript('''CREATE TABLE ai_messages(id TEXT,conversation_id TEXT,role TEXT,metadata TEXT,tool_calls TEXT);
          CREATE TABLE ai_request_usage(conversation_id TEXT,execution_id TEXT,request_id TEXT,provider TEXT,model TEXT,input_tokens INTEGER,output_tokens INTEGER,request_started_at_ms INTEGER,request_finished_at_ms INTEGER,observed_at_ms INTEGER);''')
        audit=[];calls=[]
        for number,name in enumerate(sorted(verifier.REQUIRED)):
            args={'operationId':self.run+'-prepare'} if name=='hook_prepare' else {}
            receipt={'protocol':1,'ok':True,'requestId':'request-'+str(number),'result':{'state':'DISABLED' if name=='hook_disable' else 'OPEN','hits':2,'errors':0}}
            raw=json.dumps(receipt,separators=(',',':'));result=json.dumps([{'type':'text','text':json.dumps({'content':[{'type':'text','text':raw}],'is_error':False})}])
            call={'id':'call-'+str(number),'name':verifier.PREFIX+name,'arguments':json.dumps(args),'result':result}
            metadata={'execution_id':'execution','tool_name':call['name'],'tool_args':args,'tool_result':result,'sequence':number}
            self.c.execute('INSERT INTO ai_messages VALUES(?,?,?,?,?)',(call['id'],'conversation','tool',json.dumps(metadata),None));calls.append(call)
            audit.append({'tool':name,'receiptSha256':hashlib.sha256(raw.encode()).hexdigest(),'requestId':receipt['requestId']})
        self.c.execute('INSERT INTO ai_messages VALUES(?,?,?,?,?)',('assistant','conversation','assistant',json.dumps({'execution_id':'execution'}),json.dumps(calls)))
        self.c.execute('INSERT INTO ai_request_usage VALUES(?,?,?,?,?,?,?,?,?,?)',('conversation','execution','request','synthetic','synthetic',1,1,1,2,2));self.c.commit()
        (self.root/'device-observation.json').write_text(json.dumps({'passed':True,'sentinelClientVerified':True,'runId':self.run}))
        (self.root/'sentinel-mcp-audit.jsonl').write_text(''.join(json.dumps(a)+'\n' for a in audit))

    def run_verify(self):
        with contextlib.redirect_stdout(io.StringIO()):verifier.verify(self.db,self.root)

    def test_exact_chain_and_read_only_database(self):
        before=self.db.read_bytes();self.run_verify();self.assertEqual(before,self.db.read_bytes())
        verdict=json.loads((self.root/'sentinel-ai-verdict.json').read_text());self.assertTrue(verdict['passed']);self.assertEqual(len(verdict['calls']),7)

    def test_receipt_must_match_transport_bytes(self):
        p=self.root/'sentinel-mcp-audit.jsonl';p.write_text(p.read_text().replace('"receiptSha256": "','"receiptSha256": "bad'))
        with self.assertRaisesRegex(ValueError,'AI_RECEIPT_NOT_IN_SENTINEL_TRANSPORT'):self.run_verify()

    def test_manual_tools_without_model_records_do_not_pass(self):
        self.c.execute("DELETE FROM ai_messages WHERE role='assistant'");self.c.commit()
        with self.assertRaisesRegex(ValueError,'MODEL_TOOL_CALL_NOT_PERSISTED'):self.run_verify()

    def test_missing_model_usage_does_not_pass(self):
        self.c.execute('DELETE FROM ai_request_usage');self.c.commit()
        with self.assertRaisesRegex(ValueError,'MODEL_REQUEST_USAGE_EVIDENCE_MISSING'):self.run_verify()

    def test_business_failure_cannot_be_wrapped_as_success(self):
        raw=json.dumps([{'type':'text','text':json.dumps({'content':[{'type':'text','text':json.dumps({'protocol':1,'ok':False})}],'is_error':False})}])
        with self.assertRaisesRegex(ValueError,'MCP_BUSINESS_STATUS_MISMATCH'):verifier.decode_tool_result(raw)

if __name__=='__main__':unittest.main()
