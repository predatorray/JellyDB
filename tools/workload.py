#!/usr/bin/env python3
"""Seeded open-loop characterization on an explicitly selected isolated test deployment.
Writes bounded records incrementally; fixed histogram includes scheduling delay in latency.
"""
import argparse
import base64
import concurrent.futures
import hashlib
import json
import math
import random
import resource
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--rate', type=float, default=10)
    parser.add_argument('--seed', type=int, default=1)
    parser.add_argument('--output', required=True)
    parser.add_argument('--build', required=True)
    parser.add_argument('--config-sha256', required=True)
    parser.add_argument('--delete-fraction', type=float, default=0.05, help='Fraction of mutations that delete')
    parser.add_argument('--random-values', action='store_true', help='Deterministic poorly compressible base64 corpus')
    args = parser.parse_args()
    if args.seconds <= 0 or args.rate <= 0 or not 0 <= args.delete_fraction <= 1:
        parser.error('Invalid workload bounds')
    rng = random.Random(args.seed)

    def call(method, path, data=None):
        payload = None if data is None else json.dumps(data).encode()
        request = urllib.request.Request(args.base + path, data=payload, method=method, headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read()

    namespace = json.loads(call('POST', '/v1/namespaces')[1])['id']
    root = '/v1/namespaces/' + namespace

    def token(key):
        return '~' + base64.urlsafe_b64encode(key.encode()).decode().rstrip('=')

    for n in range(100):
        call('PUT', root + '/items/' + token(str(n)), {'value': 'x' * 1024})
    start = time.monotonic()
    outstanding = set()
    dropped = 0
    counts = {'requests': 0, 'errors': 0, 'missingReads': 0, 'putBytes': 0}
    # Fixed log histogram, 1% relative resolution. Percentiles report upper bucket edges.
    histogram = [0] * 2048

    def execute(offset, method, key, value):
        began = time.monotonic()
        status = 0
        try:
            status, _ = call(method, root + '/items/' + token(key), {'value': value} if method == 'PUT' else None)
        except urllib.error.HTTPError as error:
            status = error.code
        except Exception:
            pass
        return {'scheduledSeconds': offset, 'method': method, 'bytes': len(value) if value else 0, 'status': status,
                'queueMs': (began - start - offset) * 1000, 'latencyMs': (time.monotonic() - start - offset) * 1000}

    with open(args.output + '.requests.jsonl', 'w') as records:
        def record(result):
            records.write(json.dumps(result, separators=(',', ':')) + '\n')
            counts['requests'] += 1
            expected_missing = result['status'] == 404 and result['method'] == 'GET'
            counts['missingReads'] += expected_missing
            counts['errors'] += result['status'] not in (200, 204) and not expected_missing
            counts['putBytes'] += result['bytes']
            index = min(2047, max(0, math.ceil(math.log1p(max(0, result['latencyMs'])) / math.log(1.01))))
            histogram[index] += 1

        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            for index in range(int(args.seconds * args.rate)):
                offset = index / args.rate
                key = str(rng.randrange(100))
                method = 'GET' if rng.random() < 0.8 else ('DELETE' if rng.random() < args.delete_fraction else 'PUT')
                length = 1048576 if rng.random() < 0.01 else rng.randint(1024, 4096)
                # Generate corpus in the scheduler deterministically; generation time is reflected in queue lag.
                value = None
                if method == 'PUT':
                    value = base64.b64encode(rng.randbytes((length * 3 + 3) // 4)).decode()[:length] if args.random_values else 'x' * length
                delay = start + offset - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
                ready = {future for future in outstanding if future.done()}
                for future in ready:
                    record(future.result())
                outstanding -= ready
                if len(outstanding) >= 64:
                    dropped += 1
                    continue
                outstanding.add(pool.submit(execute, offset, method, key, value))
                if index % 100 == 0:
                    records.flush()
            for future in concurrent.futures.as_completed(outstanding):
                record(future.result())

    def percentile(q):
        count = 0
        for index, n in enumerate(histogram):
            count += n
            if count >= max(1, math.ceil(counts['requests'] * q)):
                return math.expm1(index * math.log(1.01))
        return None

    report = {**counts, 'build': args.build, 'configSha256': args.config_sha256, 'seed': args.seed,
              'rate': args.rate, 'seconds': args.seconds, 'harnessDrops': dropped,
              'p50Ms': percentile(.50), 'p95Ms': percentile(.95), 'p99Ms': percentile(.99),
              'clientMaxRssKiB': resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              'corpus': 'base64 random' if args.random_values else 'repeated x',
              'limitations': 'Fixed ~1% histogram; host disk/RSS/recovery/archive metrics collected separately. 404 reads after deletes reported separately, never assumed expiry.'}
    with open(args.output, 'w') as output:
        json.dump(report, output, indent=2)
    print(json.dumps(report))


if __name__ == '__main__':
    main()
