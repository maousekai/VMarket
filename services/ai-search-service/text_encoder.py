"""Private CPU text companion. One native operation survives HTTP cancellation."""
import asyncio
import hashlib
import hmac
import json
import re
import time
from collections import deque
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from starlette.exceptions import HTTPException as StarletteHTTPException

from schemas import SearchError, error_response
from semantic import EmbeddingBusy, fingerprint, validated_vector
from settings import Settings

HTTP_CODES = {401: 'UNAUTHORIZED', 404: 'NOT_FOUND', 405: 'METHOD_NOT_ALLOWED'}


def failure(request, status, code, message):
    return error_response(SearchError(status, code, message), request.url.path)


class NativeEncoder:
    def __init__(self, settings):
        self.settings = settings

    def load(self):
        import torch
        from transformers import AutoModel, AutoTokenizer
        s = self.settings
        if s.text_provider != "native" or s.text_model != "intfloat/multilingual-e5-small" or s.text_dims != 384:
            raise ValueError("Native companion requires multilingual-e5-small / 384 dimensions")
        path = Path(s.text_path)
        manifest = json.loads((path / 'manifest.json').read_text())
        if manifest['revision'] != s.text_revision or manifest['model'] != s.text_model:
            raise ValueError("Cached text model revision mismatch")
        required = {'config.json', 'model.safetensors', 'tokenizer.json', 'tokenizer_config.json', 'special_tokens_map.json'}
        if set(manifest['files']) != required:
            raise ValueError("Incomplete model manifest")
        for name, digest in manifest['files'].items():
            hasher = hashlib.sha256()
            with (path / name).open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    hasher.update(chunk)
            if hasher.hexdigest() != digest:
                raise ValueError("Cached text model checksum mismatch")
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        self.torch = torch
        self.tokenizer = AutoTokenizer.from_pretrained(path, local_files_only=True, trust_remote_code=False)
        self.model = AutoModel.from_pretrained(path, local_files_only=True, trust_remote_code=False, use_safetensors=True).eval()

    def encode(self, text):
        tokens = self.tokenizer([text], max_length=self.settings.text_tokens, padding=True,
                                truncation=True, return_tensors='pt')
        with self.torch.inference_mode():
            hidden = self.model(**tokens).last_hidden_state
            mask = tokens['attention_mask'][..., None]
            vector = (hidden.masked_fill(~mask.bool(), 0).sum(dim=1) / mask.sum(dim=1))[0].tolist()
        return validated_vector(vector, self.settings.text_dims)


class InferenceQueue:
    def __init__(self, encode):
        self.encode = encode
        self.queues = {'query': deque(), 'catalog': deque()}
        self.event = asyncio.Event()
        self.last = 'catalog'

    async def submit(self, text, role, timeout):
        queue = self.queues[role]
        # Remove cancelled waiters before applying admission bounds.
        self.queues[role] = queue = deque(job for job in queue if not job[1].done())
        if len(queue) >= (8 if role == 'query' else 1):
            raise EmbeddingBusy()
        future = asyncio.get_running_loop().create_future()
        queue.append((text, future, time.monotonic() + timeout))
        self.event.set()
        async with asyncio.timeout(timeout):
            return await future

    async def run(self):
        while True:
            await self.event.wait()
            other = 'catalog' if self.last == 'query' else 'query'
            role = other if self.queues[other] else self.last
            if not self.queues[role]:
                self.event.clear()
                continue
            text, future, deadline = self.queues[role].popleft()
            if future.done() or time.monotonic() >= deadline:
                if not future.done(): future.set_exception(TimeoutError())
                continue
            self.last = role
            try:
                vector = await asyncio.to_thread(self.encode, text)
                if not future.done(): future.set_result(vector)
            except Exception as error:
                if not future.done(): future.set_exception(error)


def create_encoder_app(settings=None, encoder=None):
    settings = settings or Settings.load()
    encoder = encoder or NativeEncoder(settings)
    queue = InferenceQueue(encoder.encode)

    @asynccontextmanager
    async def lifespan(app):
        if not settings.internal_key:
            raise ValueError('Companion requires INTERNAL_API_KEY')
        await asyncio.to_thread(encoder.load)
        # Warm native kernels before readiness, including the maximum token shape.
        await asyncio.to_thread(encoder.encode, 'query: đồ mặc khi trời lạnh')
        await asyncio.to_thread(encoder.encode, 'passage: ' + 'áo ' * settings.text_tokens)
        runner = asyncio.create_task(queue.run())
        yield
        runner.cancel()
        await asyncio.gather(runner, return_exceptions=True)

    async def authenticate(request: Request):
        supplied = request.headers.get('X-Internal-Api-Key', '')
        if not settings.internal_key or not hmac.compare_digest(supplied.encode(), settings.internal_key.encode()):
            raise HTTPException(status_code=401, detail='Unauthorized')

    app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None,
                  dependencies=[Depends(authenticate)])

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request, error):
        return failure(request, error.status_code, HTTP_CODES.get(error.status_code, 'HTTP_ERROR'), str(error.detail))

    @app.get('/internal/health')
    async def health():
        return {'status': 'UP', 'fingerprint': fingerprint(settings)}

    @app.post('/v1/embeddings')
    async def embed(request: Request):
        pending = None
        try:
            role = request.headers.get('X-Embedding-Role')
            raw_deadline = request.headers.get('X-Embedding-Deadline-Ms', '')
            if role not in ('query', 'catalog') or not re.fullmatch(r'[0-9]{13,14}', raw_deadline):
                raise ValueError('Missing embedding deadline or role')
            budget = settings.text_query_ms / 1000 if role == 'query' else settings.text_catalog_timeout
            deadline = min(time.monotonic() + budget, time.monotonic() + int(raw_deadline) / 1000 - time.time())
            async with asyncio.timeout(1):
                raw = bytearray()
                async for chunk in request.stream():
                    if len(raw) + len(chunk) > 60000:
                        return failure(request, 413, 'INPUT_TOO_LARGE', 'Input too large')
                    raw.extend(chunk)
            body = json.loads(raw)
            if (role not in ('query', 'catalog') or body.get('model') != settings.text_model
                    or request.headers.get('X-Embedding-Fingerprint') != fingerprint(settings)
                    or not isinstance(body.get('input'), list) or len(body['input']) != 1
                    or not isinstance(body['input'][0], str) or not body['input'][0].strip()
                    or not body['input'][0].startswith('query: ' if role == 'query' else 'passage: ')):
                raise ValueError('Invalid embedding request')
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError()
            pending = asyncio.create_task(queue.submit(body['input'][0], role, remaining))
            while not pending.done():
                await asyncio.wait({pending}, timeout=0.01)
                if await request.is_disconnected():
                    return failure(request, 499, 'CALLER_DISCONNECTED', 'Caller disconnected')
            vector = await pending
            return {'model': settings.text_model, 'fingerprint': fingerprint(settings),
                    'data': [{'object': 'embedding', 'index': 0, 'embedding': vector}]}
        except EmbeddingBusy:
            return failure(request, 429, 'ENCODER_BUSY', 'Encoder busy')
        except TimeoutError:
            return failure(request, 503, 'ENCODER_DEADLINE', 'Encoder deadline exceeded')
        except (ValueError, KeyError, TypeError, AttributeError):
            return failure(request, 400, 'INVALID_EMBEDDING_REQUEST', 'Invalid embedding request')
        finally:
            if pending is not None:
                if not pending.done(): pending.cancel()
                await asyncio.gather(pending, return_exceptions=True)

    return app
