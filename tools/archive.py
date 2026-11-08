#!/usr/bin/env python3
"""Email-only operational archive. Stdlib; never sends unless the send subcommand is invoked.
Use a local SMTP sink for tests. Exported messages are untrusted inputs.
"""
import argparse, base64, contextlib, email, email.policy, email.utils, fcntl, gzip, hashlib
import io, json, mailbox, os, pathlib, re, shutil, smtplib, socket, ssl, subprocess, sys, time, uuid
from email.message import EmailMessage

MAX_MESSAGE = 8 * 1024 * 1024
MAX_RAW = 16 * 1024 * 1024
NAME = re.compile(r"segment-([0-9]{12})\.jsonl\.gz\Z")


def atomic(path, data):
    path = pathlib.Path(path)
    temp = path.with_name(path.name + '.tmp')
    with open(temp, 'wb') as out:
        out.write(data); out.flush(); os.fsync(out.fileno())
    os.replace(temp, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)


def save(path, value):
    atomic(path, json.dumps(value, sort_keys=True, separators=(',', ':')).encode())


def read(path, default=None):
    return json.loads(path.read_bytes()) if path.exists() else default


def size(root):
    return sum(p.stat().st_size for p in root.rglob('*') if p.is_file())


class Spool:
    def __init__(self, root, limit=256*1024*1024):
        self.root = pathlib.Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.root, 0o700)
        self.segments = self.root / 'segments'; self.segments.mkdir(exist_ok=True)
        self.batches = self.root / 'batches'; self.batches.mkdir(exist_ok=True)
        self.limit = limit
        # Recovery: a published immutable batch owns its segments. Clean duplicate source copies
        # left by a crash between publishing the batch and unlinking sources.

    @contextlib.contextmanager
    def locked(self):
        with open(self.root / '.lock', 'a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            yield

    def capacity(self, additional):
        if size(self.root) + additional > self.limit:
            save(self.root / 'pressure.json', {'time':time.time(), 'reason':'spool_capacity', 'requiredBytes':additional})
            raise RuntimeError('Spool full: pending history retained; stop public admissions; journal backlog may fill')

    def segment(self, records):
        if not records: return
        raw = b''.join(json.dumps(r, ensure_ascii=True, separators=(',', ':')).encode()+b'\n' for r in records)
        if len(raw) > MAX_RAW: raise ValueError('Oversize event/segment: retained in journal; operator action required')
        zipped = gzip.compress(raw, mtime=0)
        self.capacity(len(zipped)*2+8192)
        seq = read(self.root / 'sequence.json', 0) + 1
        # Reserve identity before use. A crash can produce a detectable sequence gap, never identity reuse.
        save(self.root / 'sequence.json', seq)
        name = f'segment-{seq:012d}.jsonl.gz'
        atomic(self.segments / name, zipped)
        # Cursor advances only after segment is durable. Replay after a crash is deduplicated by journal cursor.
        save(self.root / 'cursor.json', records[-1].get('__CURSOR'))

    def collect(self, units):
        cursor = read(self.root / 'cursor.json')
        command = ['journalctl', '--no-pager', '--output=json']
        for unit in units: command += ['--unit', unit]
        if cursor: command += ['--after-cursor', cursor]
        # Missing/vacuumed cursor is an explicit failure; never silently fall back to newest events.
        records=[]; count=0; started=time.monotonic()
        with subprocess.Popen(command, stdout=subprocess.PIPE) as proc:
            try:
                while True:
                    line=proc.stdout.readline(MAX_RAW+1)
                    if not line: break
                    if len(line)>MAX_RAW: raise RuntimeError('Journal event too large; cursor not advanced')
                    event=json.loads(line)
                    # Collector trusts baseline logger redaction; retains complete emitted messages, including multiline errors.
                    records.append(event); count+=len(line)
                    if count>=4*1024*1024 or time.monotonic()-started>=300:
                        self.segment(records); records=[]; count=0; started=time.monotonic()
                if proc.wait()!=0: raise RuntimeError('journalctl failed; cursor may have been vacuumed: suspected gap')
                self.segment(records)
            except BaseException:
                proc.terminate(); proc.wait(); raise

    def batch(self, sender, recipient, host, force=False, threshold=5*1024*1024, max_message=MAX_MESSAGE):
        for value in (sender, recipient, host):
            if '\r' in value or '\n' in value: raise ValueError('Header injection')
        if not re.fullmatch(r'[A-Za-z0-9.-]{1,128}',host): raise ValueError('Invalid host identity')
        referenced=set()
        for d in self.batches.iterdir():
            if d.is_dir() and not d.name.startswith('.'):
                for attachment in read(d/'manifest.json')['attachments']:
                    referenced.add(attachment['name'])
                    duplicate=self.segments/attachment['name']
                    if duplicate.exists():
                        if hashlib.sha256(duplicate.read_bytes()).hexdigest()!=attachment['sha256']:
                            raise RuntimeError('Conflicting segment after crash')
                        duplicate.unlink()
        files=[p for p in sorted(self.segments.iterdir()) if NAME.fullmatch(p.name) and p.name not in referenced]
        if not files: return None
        last=read(self.root/'last-batch.json',0)
        total=sum(p.stat().st_size for p in files)
        if not force and total<threshold and time.time()-last<3600: return None
        chosen=[]; compressed=0
        for p in files:
            n=p.stat().st_size
            if chosen and compressed+n>threshold: break
            chosen.append(p); compressed+=n
        batch_id=uuid.uuid4().hex
        manifest={'version':1,'batchId':batch_id,'host':host,'created':time.time(),'attachments':[],
                  'metadata':{'archiveVersion':'1','candyboxCommit':'c6392d5130ec5b4ec5c3f63d161b449a9bc29fc3','applicationBuild':os.environ.get('JELLY_BUILD_ID','unknown')[:128],'redactedConfigFingerprint':os.environ.get('JELLY_CONFIG_SHA256','unknown')[:64],'deliveryGuarantee':'SMTP acceptance only; mailbox verification unavailable'}}
        for p in chosen:
            content=p.read_bytes()
            with gzip.GzipFile(fileobj=io.BytesIO(content)) as gz: raw=gz.read(MAX_RAW+1)
            if len(raw)>MAX_RAW: raise ValueError('Raw segment limit')
            events=[json.loads(line) for line in raw.splitlines()]
            manifest['attachments'].append({'name':p.name,'service':'journal','sequence':int(NAME.fullmatch(p.name)[1]),
                'bytes':len(content),'rawBytes':len(raw),'sha256':hashlib.sha256(content).hexdigest(),
                'firstUtcMicros':events[0].get('__REALTIME_TIMESTAMP'),'lastUtcMicros':events[-1].get('__REALTIME_TIMESTAMP'),
                'bootIds':sorted({e.get('_BOOT_ID','unknown') for e in events})})
        msg=EmailMessage(policy=email.policy.SMTP)
        msg['From']=sender; msg['To']=recipient; msg['Subject']=f'JellyDB logs {host} {batch_id}'
        msg['Message-ID']=f'<jellydb.{batch_id}@{host}>'; msg['X-JellyDB-Batch-ID']=batch_id
        msg['Date']=email.utils.formatdate(localtime=False)
        msg.set_content('Operational archive; validate manifest before retrieval. No mailbox verification is implied.')
        msg.add_attachment(json.dumps(manifest,sort_keys=True).encode(),maintype='application',subtype='json',filename='manifest.json')
        for p in chosen: msg.add_attachment(p.read_bytes(),maintype='application',subtype='gzip',filename=p.name)
        payload=msg.as_bytes()
        if len(payload)>max_message:
            if len(chosen)>1: return self.batch(sender,recipient,host,True,max(1,compressed//2),max_message)
            raise RuntimeError('One immutable segment exceeds provider MIME limit; no truncation; change rotation limit')
        self.capacity(len(payload)+16384)
        staging=self.batches/('.'+batch_id); staging.mkdir()
        atomic(staging/'message.eml',payload); save(staging/'manifest.json',manifest)
        save(staging/'state.json',{'state':'pending','attempts':0,'nextAttempt':0,'uncertain':False})
        os.rename(staging,self.batches/batch_id)
        fd=os.open(self.batches,os.O_RDONLY)
        try: os.fsync(fd)
        finally: os.close(fd)
        for p in chosen: p.unlink()
        save(self.root/'last-batch.json',time.time())
        return batch_id

    def send(self, host, port, user, password, plain_sink=False):
        if plain_sink and host not in ('127.0.0.1','localhost','::1'): raise ValueError('Plain SMTP only permitted for loopback test sinks')
        now=time.time()
        for d in sorted(self.batches.iterdir()):
            if not d.is_dir() or d.name.startswith('.'): continue
            state=read(d/'state.json')
            if state['state']!='pending' or state.get('permanentFailure') or state['nextAttempt']>now: continue
            state['attempts']+=1; state['uncertain']=True
            state['nextAttempt']=now+min(3600,30*2**min(state['attempts'],7))
            save(d/'state.json',state)  # persist before network effects
            try:
                with smtplib.SMTP(host,port,timeout=30) as smtp:
                    smtp.ehlo()
                    if not plain_sink: smtp.starttls(context=ssl.create_default_context()); smtp.ehlo()
                    if user: smtp.login(user,password)
                    message=d.joinpath('message.eml').read_bytes()
                    parsed=email.message_from_bytes(message,policy=email.policy.default)
                    refused=smtp.sendmail(email.utils.parseaddr(parsed['From'])[1],[email.utils.parseaddr(parsed['To'])[1]],message)
                    if refused: raise RuntimeError('Recipient refused')
                    # QUIT failure cannot undo an already-observed successful DATA acknowledgment.
                    state.update(state='SMTP-accepted',acceptedAt=time.time(),uncertain=False)
                    save(d/'state.json',state)
            except Exception as exc:
                if state['state']=='SMTP-accepted': continue
                code=getattr(exc,'smtp_code',None)
                if isinstance(exc,smtplib.SMTPRecipientsRefused):
                    codes=[v[0] for v in exc.recipients.values()]
                    code=max(codes) if codes else None
                state['lastErrorType']=type(exc).__name__; state['smtpCode']=code
                if isinstance(code,int) and 500<=code<600: state['permanentFailure']=True
                # Raw SMTP errors may include addresses/credentials; expose structured classes/codes only.
                save(d/'state.json',state)
        return self.status()

    def bounce(self,batch_id):
        if not re.fullmatch('[0-9a-f]{32}',batch_id): raise ValueError('Invalid batch ID')
        d=self.batches/batch_id
        if not d.exists():
            save(self.root/'suspected-gap.json',{'batchId':batch_id,'time':time.time(),'reason':'bounce_after_local_expiry'})
            return
        state=read(d/'state.json'); state.update(state='pending',nextAttempt=0,permanentFailure=False,bounceAt=time.time())
        save(d/'state.json',state)
        save(self.root/'pressure.json',{'reason':'recipient_bounce','batchId':batch_id,'time':time.time()})

    def prune(self, safety_seconds=86400):
        # Explicit weaker guarantee: accepted is not mailbox-verified. Never prune pending batches.
        for d in self.batches.iterdir():
            if not d.is_dir() or d.name.startswith('.'): continue
            state=read(d/'state.json')
            if state['state']=='SMTP-accepted' and time.time()-state['acceptedAt']>=safety_seconds:
                marker={'batchId':d.name,'reason':'accepted_unverified_safety_window_expired','time':time.time()}
                # Append compact retention evidence, rotate/export this through the baseline journal in production.
                with open(self.root/'retention.jsonl','ab') as out: out.write(json.dumps(marker).encode()+b'\n'); out.flush(); os.fsync(out.fileno())
                shutil.rmtree(d)

    def status(self):
        pending=0; oldest=None; accepted=None; retries=0; permanent=0; uncertain=0
        for d in self.batches.iterdir():
            if not d.is_dir() or d.name.startswith('.'): continue
            state=read(d/'state.json'); retries+=max(0,state['attempts']-1)
            if state['state']=='pending':
                pending+=d.joinpath('message.eml').stat().st_size
                created=read(d/'manifest.json')['created']; oldest=created if oldest is None else min(oldest,created)
            if state['state']=='SMTP-accepted': accepted=max(accepted or 0,state['acceptedAt'])
            permanent+=bool(state.get('permanentFailure')); uncertain+=bool(state.get('uncertain'))
        return {'pendingBytes':pending,'unbatchedBytes':size(self.segments),'oldestPending':oldest,'lastSmtpAcceptance':accepted,
                'retryCount':retries,'permanentFailures':permanent,'uncertainSubmissions':uncertain,'mailboxVerification':'unavailable',
                'spoolBytes':size(self.root),'pressure':read(self.root/'pressure.json'), 'suspectedGap':read(self.root/'suspected-gap.json')}


def retrieve(paths, output, service=None, start=0, end=2**63-1):
    output=pathlib.Path(output); output.mkdir(parents=True,exist_ok=True)
    seen_batches={}; seen_segments={}; seen_events=set(); events=[]; sequences={}
    for path in map(pathlib.Path,paths):
        if path.suffix=='.mbox':
            if path.stat().st_size>64*MAX_MESSAGE: raise ValueError('Split mbox exports into <=512 MiB chunks')
            messages=mailbox.mbox(path,create=False)
        else:
            if path.stat().st_size>MAX_MESSAGE: raise ValueError('Message size bound')
            messages=[email.message_from_bytes(path.read_bytes(),policy=email.policy.default)]
        for message in messages:
            parts={}; total=0
            for part in message.walk():
                name=part.get_filename()
                if not name: continue
                if name!='manifest.json' and not NAME.fullmatch(name): raise ValueError('Unexpected attachment name')
                if name in parts: raise ValueError('Duplicate attachment')
                data=part.get_payload(decode=True)
                if data is None: raise ValueError('Invalid attachment')
                total+=len(data)
                if total>MAX_MESSAGE: raise ValueError('Attachment size bound')
                parts[name]=data
            if 'manifest.json' not in parts: continue  # unrelated exported mail
            manifest=json.loads(parts['manifest.json']); batch=manifest['batchId']; host=manifest['host']
            if manifest.get('version')!=1 or not re.fullmatch('[0-9a-f]{32}',batch): raise ValueError('Invalid manifest identity')
            digest=hashlib.sha256(parts['manifest.json']).hexdigest()
            if batch in seen_batches:
                if seen_batches[batch]!=digest: raise ValueError('Batch identity reused with different content')
            seen_batches[batch]=digest
            expected={'manifest.json'}
            for spec in manifest['attachments']:
                name=spec['name']; expected.add(name)
                if not NAME.fullmatch(name) or spec['sequence']!=int(NAME.fullmatch(name)[1]): raise ValueError('Invalid segment identity')
                content=parts[name]
                if len(content)!=spec['bytes'] or hashlib.sha256(content).hexdigest()!=spec['sha256']: raise ValueError('Attachment checksum/size mismatch')
                identity=(host,spec['sequence'])
                if identity in seen_segments and seen_segments[identity]!=spec['sha256']: raise ValueError('Segment identity reused')
                if identity in seen_segments: continue
                seen_segments[identity]=spec['sha256']; sequences.setdefault(host,set()).add(spec['sequence'])
                with gzip.GzipFile(fileobj=io.BytesIO(content)) as gz: raw=gz.read(MAX_RAW+1)
                if len(raw)>MAX_RAW or len(raw)!=spec['rawBytes']: raise ValueError('Decompression bound/size mismatch')
                for offset,line in enumerate(raw.splitlines()):
                    event=json.loads(line); identity=(host,event.get('__CURSOR',f'{name}:{offset}'))
                    if identity in seen_events: continue
                    seen_events.add(identity)
                    timestamp=int(event.get('__REALTIME_TIMESTAMP',0))
                    if start<=timestamp<=end and (service is None or event.get('_SYSTEMD_UNIT')==service): events.append((timestamp,host,spec['sequence'],offset,event))
            if set(parts)!=expected: raise ValueError('Unexpected manifest attachments')
    gaps=[]
    for host,seqs in sequences.items():
        ordered=sorted(seqs)
        for a,b in zip(ordered,ordered[1:]):
            if b>a+1: gaps.append({'host':host,'from':a+1,'to':b-1})
    # Keep per-host segment order even if wall clocks regress; cross-host timing remains approximate.
    events.sort(key=lambda e:(e[1],e[2],e[3]))
    atomic(output/'events.jsonl',b''.join(json.dumps({'host':host,'event':event},ensure_ascii=True).encode()+b'\n' for _,host,_,_,event in events))
    report={'batches':len(seen_batches),'events':len(events),'gaps':gaps,'limits':'Cannot detect missing leading/trailing batches without an external expected sequence inventory'}
    save(output/'retrieval.json',report)
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spool',default='/var/lib/jellydb/archive')
    parser.add_argument('--limit',type=int,default=256*1024*1024)
    subs=parser.add_subparsers(dest='cmd',required=True)
    collect=subs.add_parser('collect'); collect.add_argument('--unit',action='append',required=True)
    batch=subs.add_parser('batch'); batch.add_argument('--force',action='store_true')
    batch.add_argument('--sender',required=True); batch.add_argument('--recipient',required=True); batch.add_argument('--host-id',required=True)
    batch.add_argument('--max-mime-bytes',type=int,default=MAX_MESSAGE)
    send=subs.add_parser('send'); send.add_argument('--smtp-host',required=True); send.add_argument('--smtp-port',type=int,default=587); send.add_argument('--local-sink',action='store_true')
    subs.add_parser('status'); subs.add_parser('prune')
    bounce=subs.add_parser('bounce'); bounce.add_argument('batch_id')
    extract=subs.add_parser('retrieve'); extract.add_argument('messages',nargs='+'); extract.add_argument('--output',required=True); extract.add_argument('--service'); extract.add_argument('--start-micros',type=int,default=0); extract.add_argument('--end-micros',type=int,default=2**63-1)
    args=parser.parse_args()
    if args.cmd=='retrieve': print(json.dumps(retrieve(args.messages,args.output,args.service,args.start_micros,args.end_micros))); return
    spool=Spool(args.spool,args.limit)
    with spool.locked():
        if args.cmd=='collect': spool.collect(args.unit)
        elif args.cmd=='batch': print(spool.batch(args.sender,args.recipient,args.host_id,args.force,max_message=args.max_mime_bytes))
        elif args.cmd=='send': print(json.dumps(spool.send(args.smtp_host,args.smtp_port,os.environ.get('SMTP_USER'),os.environ.get('SMTP_PASSWORD'),args.local_sink)))
        elif args.cmd=='status': print(json.dumps(spool.status()))
        elif args.cmd=='prune': spool.prune()
        elif args.cmd=='bounce': spool.bounce(args.batch_id)

if __name__=='__main__': main()
