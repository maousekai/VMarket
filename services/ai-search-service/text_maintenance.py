"""Operator-only weight caching and Vietnamese semantic quality qualification."""
import asyncio
import hashlib
import json
import time
from pathlib import Path

import httpx

from semantic import TextEmbeddings
from search import filters


def cache_weights(settings):
    if settings.text_provider != 'native' or settings.text_model != 'intfloat/multilingual-e5-small':
        raise ValueError('Weight caching requires the native multilingual-e5-small model')
    path = Path(settings.text_path)
    if path.exists() and any(path.iterdir()):
        raise ValueError('Choose an empty TEXT_EMBEDDING_PATH; existing cached weights are preserved')
    path.mkdir(parents=True, exist_ok=True)
    names = ('config.json', 'model.safetensors', 'tokenizer.json', 'tokenizer_config.json', 'special_tokens_map.json')
    base = 'https://huggingface.co/' + settings.text_model
    manifest = {'model': settings.text_model, 'revision': settings.text_revision, 'files': {}}
    with httpx.Client(trust_env=False, follow_redirects=True, timeout=settings.weights_timeout) as client:
        response = client.get('https://huggingface.co/api/models/' + settings.text_model + '/revision/' + settings.text_revision,
                              params={'blobs': 'true'})
        response.raise_for_status()
        metadata = response.json()
        if metadata['sha'] != settings.text_revision:
            raise ValueError('Model revision mismatch')
        files = {f['rfilename']: f for f in metadata['siblings']}
        for name in names:
            temporary = path / (name + '.download')
            try:
                sha256, git_blob = hashlib.sha256(), hashlib.sha1()
                expected_size = files[name]['size']
                if not 0 < expected_size <= 600_000_000:
                    raise ValueError('Unexpected model file size')
                git_blob.update(f'blob {expected_size}\0'.encode())
                count, started = 0, time.monotonic()
                with client.stream('GET', base + '/resolve/' + settings.text_revision + '/' + name) as response:
                    response.raise_for_status()
                    with temporary.open('wb') as stream:
                        for chunk in response.iter_bytes():
                            count += len(chunk)
                            if count > expected_size or time.monotonic() - started > 300:
                                raise ValueError('Model download limit exceeded')
                            sha256.update(chunk)
                            git_blob.update(chunk)
                            stream.write(chunk)
                expected = files[name].get('lfs', {}).get('sha256')
                if (count != expected_size or (expected and sha256.hexdigest() != expected)
                        or (not expected and git_blob.hexdigest() != files[name]['blobId'])):
                    raise ValueError('Official model checksum mismatch')
                temporary.replace(path / name)
                manifest['files'][name] = sha256.hexdigest()
            finally:
                temporary.unlink(missing_ok=True)
    (path / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print('Verified pinned text weights cached; runtime loads offline only')


async def probe(settings):
    encoder = TextEmbeddings(settings)
    try:
        vector = await encoder.query('đồ mặc khi trời lạnh')
        if vector is None:
            raise ValueError('Text embedding probe failed within query budget')
        print(json.dumps({'fingerprint': encoder.fingerprint, 'dimensions': len(vector)}))
    finally:
        await encoder.close()


async def calibrate_text(store, settings, manifest):
    encoder = TextEmbeddings(settings)
    groups, seen = {}, set()
    try:
        for group in ('calibration', 'heldOut'):
            rows = []
            for query in manifest[group]:
                text = query['keyword'].strip()
                if not 1 <= len(text) <= 200 or text.casefold() in seen:
                    raise ValueError('Quality queries must be valid and disjoint')
                seen.add(text.casefold())
                vector = await encoder.query(text)
                if vector is None:
                    raise ValueError('Quality evaluation forbids embedding fallback')
                params = {'keyword': text, 'sort': 'RELEVANCE', 'page': 0, 'size': 5}
                baseline = store.keyword(params)['results']
                acceptable = set(query['acceptableProductIds'])
                miss = bool(acceptable) and not any(h['id'] in acceptable for h in baseline)
                rows.append({'params': params, 'vector': vector, 'acceptable': acceptable, 'miss': miss,
                             'baselineCorrect': bool(acceptable) and not miss})
            if (sum(bool(r['acceptable']) for r in rows) < 10 or sum(not r['acceptable'] for r in rows) < 10
                    or sum(r['miss'] for r in rows) < 5):
                raise ValueError('Each split requires 10 intent, 10 unrelated and 5 lexical-miss queries')
            groups[group] = rows
        # Candidate thresholds come exclusively from calibration similarities.
        choices = {-1.0, 1.0}
        name, _ = store.meta()
        for row in groups['calibration']:
            result = store.client.search(index=name, body={'size': 5, '_source': ['id'], 'query': {'script_score': {
                'query': {'bool': {'filter': [*filters({}), {'term': {'textState': 'ready'}},
                    {'term': {'textFingerprint': encoder.fingerprint}}, {'exists': {'field': 'textVector'}}]}},
                'script': {'source': "cosineSimilarity(params.v, 'textVector') + 1", 'params': {'v': row['vector']}}}}})
            choices.update(max(-1, min(1, h['_score'] - 1)) for h in result['hits']['hits'])

        def evaluate(rows, threshold):
            positive = misses = correct = miss_correct = negative = false = baseline_correct = 0
            for row in rows:
                before = store.semantic_used
                hits = store.keyword(row['params'], vector=row['vector'], fingerprint=encoder.fingerprint,
                                     threshold=threshold)['results']
                if store.semantic_used != before + 1:
                    raise ValueError('Quality evaluation forbids retrieval fallback')
                found = any(h['id'] in row['acceptable'] for h in hits)
                if row['acceptable']:
                    positive += 1
                    correct += found
                    baseline_correct += row['baselineCorrect']
                    misses += row['miss']
                    miss_correct += row['miss'] and found
                else:
                    negative += 1
                    false += bool(hits)
            return {'recallAt5': correct / positive, 'baselineMissRecallAt5': miss_correct / misses,
                    'lexicalRecallAt5': baseline_correct / positive, 'falseAcceptance': false / negative}

        evaluated = [(threshold, evaluate(groups['calibration'], threshold)) for threshold in sorted(choices)]
        eligible = [(t, r) for t, r in evaluated if r['falseAcceptance'] <= 0.1]
        candidates = eligible or evaluated
        threshold, calibration = max(candidates, key=lambda item: (-item[1]['falseAcceptance'], item[1]['baselineMissRecallAt5'], item[1]['recallAt5'], item[0]))
        held = evaluate(groups['heldOut'], threshold)
        validated = bool(eligible) and held['recallAt5'] >= 0.8 and held['baselineMissRecallAt5'] >= 0.8 and held['falseAcceptance'] <= 0.1
        return {'fingerprint': encoder.fingerprint, 'threshold': threshold, 'validated': validated,
                'calibration': calibration, 'heldOut': held, 'llmEnabled': False}
    finally:
        await encoder.close()
