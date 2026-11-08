import email, email.policy, importlib.util, json, pathlib, socket, tempfile, threading, unittest
from unittest.mock import patch
from archive import Spool, retrieve, MAX_MESSAGE


class Sink:
    """Tiny local SMTP sink; controllable DATA rejection/disconnect; never relays mail."""
    def __init__(self, mode='accept'):
        self.mode=mode; self.messages=[]; self.sock=socket.socket(); self.sock.bind(('127.0.0.1',0)); self.sock.listen(); self.port=self.sock.getsockname()[1]
        self.thread=threading.Thread(target=self.run,daemon=True); self.thread.start()
    def run(self):
        connection,_=self.sock.accept()
        with connection,connection.makefile('rwb') as stream:
            def send(s): stream.write(s.encode()+b'\r\n'); stream.flush()
            send('220 local test')
            while True:
                line=stream.readline()
                if not line: break
                command=line.decode().strip().upper()
                if command.startswith('EHLO'): send('250 local')
                elif command.startswith(('MAIL','RCPT','RSET')): send('250 ok')
                elif command=='DATA':
                    send('354 send'); data=[]
                    while True:
                        line=stream.readline()
                        if line in (b'.\r\n',b''): break
                        data.append(line[1:] if line.startswith(b'..') else line)
                    self.messages.append(b''.join(data))
                    if self.mode=='disconnect': break
                    send('550 rejected' if self.mode=='reject' else '451 temporary' if self.mode=='temporary' else '250 queued')
                elif command=='QUIT': send('221 bye'); break
                else: send('250 ok')
        self.sock.close()
    def close(self): self.thread.join(timeout=5); self.sock.close()


class ArchiveTest(unittest.TestCase):
    def setUp(self): self.temp=tempfile.TemporaryDirectory(); self.root=pathlib.Path(self.temp.name); self.spool=Spool(self.root/'spool')
    def tearDown(self): self.temp.cleanup()
    def record(self, cursor='c1'):
        return {'__CURSOR':cursor,'__REALTIME_TIMESTAMP':'1000000','_SYSTEMD_UNIT':'jellydb.service','_BOOT_ID':'boot1','MESSAGE':'error\n  at synthetic.stack(Frame.java:1)\nCaused by: test'}
    def batch(self):
        self.spool.segment([self.record()]); return self.spool.batch('sender@example.invalid','archive@example.invalid','test-host',True)
    def test_delivery_and_duplicate_retrieval(self):
        batch=self.batch(); directory=self.spool.batches/batch
        original=(directory/'message.eml').read_bytes(); sink=Sink()
        try: status=self.spool.send('127.0.0.1',sink.port,None,None,True)
        finally: sink.close()
        self.assertEqual(0,status['pendingBytes']); self.assertEqual('SMTP-accepted',json.loads((directory/'state.json').read_bytes())['state'])
        self.assertEqual(original,(directory/'message.eml').read_bytes())
        exported=self.root/'export.eml'; exported.write_bytes(sink.messages[0]); report=retrieve([exported,exported],self.root/'restored')
        self.assertEqual(1,report['events']); self.assertEqual(1,report['batches']); restored=json.loads((self.root/'restored/events.jsonl').read_bytes())
        self.assertEqual(self.record()['MESSAGE'],restored['event']['MESSAGE'])
        self.assertEqual('unavailable',status['mailboxVerification'])
    def test_uncertain_submission_restart_preserves_identity(self):
        batch=self.batch(); directory=self.spool.batches/batch; original=(directory/'message.eml').read_bytes()
        sink=Sink('disconnect')
        try: self.spool.send('127.0.0.1',sink.port,None,None,True)
        finally: sink.close()
        state=json.loads((directory/'state.json').read_bytes()); self.assertEqual('pending',state['state']); self.assertTrue(state['uncertain'])
        state['nextAttempt']=0; (directory/'state.json').write_text(json.dumps(state))
        restarted=Spool(self.spool.root); sink=Sink()
        try: restarted.send('127.0.0.1',sink.port,None,None,True)
        finally: sink.close()
        self.assertEqual(original,(directory/'message.eml').read_bytes())
        self.assertEqual(original,sink.messages[0])
    def test_permanent_rejection_and_bounce(self):
        batch=self.batch(); sink=Sink('reject')
        try: status=self.spool.send('127.0.0.1',sink.port,None,None,True)
        finally: sink.close()
        self.assertEqual(1,status['permanentFailures']); self.assertGreater(status['pendingBytes'],0)
        self.spool.bounce(batch); self.assertEqual(0,self.spool.status()['permanentFailures'])
        self.spool.bounce('0'*32); self.assertIsNotNone(self.spool.status()['suspectedGap'])
    def test_temporary_failure_and_connection_outage(self):
        batch=self.batch(); sink=Sink('temporary')
        try: status=self.spool.send('127.0.0.1',sink.port,None,None,True)
        finally: sink.close()
        self.assertGreater(status['pendingBytes'],0); self.assertEqual(0,status['permanentFailures'])
        directory=self.spool.batches/batch
        state=json.loads((directory/'state.json').read_bytes()); state['nextAttempt']=0
        (directory/'state.json').write_text(json.dumps(state))
        reserved=socket.socket(); reserved.bind(('127.0.0.1',0))
        try: self.spool.send('127.0.0.1',reserved.getsockname()[1],None,None,True)
        finally: reserved.close()
        self.assertGreater(self.spool.status()['pendingBytes'],0)
        self.assertEqual(1,self.spool.status()['retryCount'])
    def test_accepted_safety_expiry_is_explicit_and_unverified(self):
        batch=self.batch(); directory=self.spool.batches/batch; sink=Sink()
        try: self.spool.send('127.0.0.1',sink.port,None,None,True)
        finally: sink.close()
        state=json.loads((directory/'state.json').read_bytes()); state['acceptedAt']=0
        (directory/'state.json').write_text(json.dumps(state)); self.spool.prune()
        self.assertFalse(directory.exists())
        self.assertIn('accepted_unverified_safety_window_expired',(self.spool.root/'retention.jsonl').read_text())
        self.assertEqual('unavailable',self.spool.status()['mailboxVerification'])
    def test_full_spool_never_deletes_pending(self):
        batch=self.batch(); original=(self.spool.batches/batch/'message.eml').read_bytes(); small=Spool(self.spool.root,1)
        with self.assertRaises(RuntimeError): small.segment([self.record('c2')])
        small.prune(0); self.assertEqual(original,(small.batches/batch/'message.eml').read_bytes())
        self.assertIsNotNone(small.status()['pressure'])
    def test_bad_checksum_and_unsafe_attachment(self):
        batch=self.batch(); message=email.message_from_bytes((self.spool.batches/batch/'message.eml').read_bytes(),policy=email.policy.default)
        for part in message.iter_attachments():
            if part.get_filename().endswith('.gz'):
                part.set_payload('eA=='); break
        bad=self.root/'bad.eml'; bad.write_bytes(message.as_bytes())
        with self.assertRaises(ValueError): retrieve([bad],self.root/'out')
        message.add_attachment(b'x',maintype='application',subtype='octet-stream',filename='../escape')
        bad.write_bytes(message.as_bytes())
        with self.assertRaises(ValueError): retrieve([bad],self.root/'out2')
    def test_empty_batches_size_limit_and_mail_count(self):
        self.assertIsNone(self.spool.batch('a@b','c@d','test-host',True))
        self.spool.segment([self.record()])
        with self.assertRaises(RuntimeError): self.spool.batch('a@b','c@d','test-host',True,max_message=100)
        self.assertEqual(1,len(list(self.spool.segments.iterdir())))
        batch=self.spool.batch('a@b','c@d','test-host',True)
        self.spool.segment([self.record('c2')])
        self.assertIsNone(self.spool.batch('a@b','c@d','test-host'))
    def test_gap_detection_and_conflicting_batch(self):
        first=self.batch(); self.spool.segment([self.record('c2')]); self.spool.segment([self.record('c3')])
        second=self.spool.batch('a@b','c@d','test-host',True)
        # Exclude segment 2 by constructing a separate export containing only segment 3.
        message=email.message_from_bytes((self.spool.batches/second/'message.eml').read_bytes(),policy=email.policy.default)
        manifest=json.loads(next(p for p in message.iter_attachments() if p.get_filename()=='manifest.json').get_payload(decode=True))
        manifest['attachments']=manifest['attachments'][1:]
        keep=[]
        for part in message.get_payload():
            name=part.get_filename()
            if name=='manifest.json':
                part.set_payload(__import__('base64').b64encode(json.dumps(manifest).encode()).decode()); keep.append(part)
            elif name=='segment-000000000002.jsonl.gz': continue
            else: keep.append(part)
        message.set_payload(keep); exported=self.root/'gap.eml'; exported.write_bytes(message.as_bytes())
        report=retrieve([self.spool.batches/first/'message.eml',exported],self.root/'gap-out')
        self.assertEqual([{'host':'test-host','from':2,'to':2}],report['gaps'])

if __name__=='__main__': unittest.main()
