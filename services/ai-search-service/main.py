import asyncio
import logging
import tempfile
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from python_multipart.exceptions import MultipartParseError

from event_consumer import Workers
from contract import HEALTH_PATH, IMAGE_PATH, SEARCH_PATH, SUGGESTIONS_PATH, UPLOAD_TIMEOUT_SECONDS
from diagnostics import log_failure
from images import ImageEncoder
from llm import Expander
from schemas import MAX_BODY, MAX_FILE, SearchError, error_response, image_too_large, parse_query
from search import SearchStore, image_threshold
from settings import ROOT, Settings

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("elastic_transport.transport").setLevel(logging.WARNING)
logging.getLogger("pika").setLevel(logging.WARNING)


def create_app(settings=None, store=None, encoder=None, expander=None, run_workers=True):
    settings = settings or Settings.load()
    store = store or SearchStore(settings)
    encoder = encoder or ImageEncoder(settings)
    expander = expander or Expander(settings)
    workers = Workers(store, encoder) if run_workers else None

    @asynccontextmanager
    async def lifespan(app):
        temp = ROOT / "tmp"
        if temp.is_symlink():
            raise ValueError("Service temporary directory cannot be a symlink")
        temp.mkdir(exist_ok=True)
        for stale in temp.glob("tmp*"):
            if stale.is_file() and not stale.is_symlink():
                stale.unlink()
        tempfile.tempdir = str(temp)
        if workers:
            workers.start()
        yield
        if workers:
            await asyncio.to_thread(workers.close)
        await expander.client.aclose()
        store.client.close()

    app = FastAPI(title="VMarket AI Search", version="1.0.0", lifespan=lifespan)
    app.state.store, app.state.encoder, app.state.expander = store, encoder, expander
    app.state.image_busy = False
    app.add_middleware(CORSMiddleware, allow_origins=[s.strip() for s in settings.cors.split(",") if s.strip()],
                       allow_credentials=True, allow_methods=["GET", "POST"], allow_headers=["Content-Type", "Authorization"])

    @app.exception_handler(SearchError)
    async def search_error(request, error):
        return error_response(error, request.url.path)

    async def dependency_call(function, *args):
        try:
            return await asyncio.to_thread(function, *args)
        except SearchError:
            raise
        except Exception as error:
            log_failure("search_dependency_failed", error)
            raise SearchError(503, "SEARCH_UNAVAILABLE", "Search dependency unavailable") from None

    @app.get(SEARCH_PATH)
    async def keyword_search(request: Request):
        params = parse_query(request.query_params, "keyword")
        terms = await expander.expand_query(params["keyword"])
        return await dependency_call(store.keyword, params, terms)

    @app.get(SUGGESTIONS_PATH)
    async def suggestions(request: Request):
        return await dependency_call(store.suggestions, parse_query(request.query_params, "suggestions"))

    @app.post(IMAGE_PATH)
    async def image_search(request: Request):
        params = parse_query(request.query_params, "image")
        if app.state.image_busy:
            raise SearchError(429, "SEARCH_BUSY", "Another image request is running")
        app.state.image_busy = True
        async def process():
            if not encoder.ready:
                raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Image encoder unavailable")
            data = await read_upload(request)
            try:
                vector = await asyncio.to_thread(encoder.embed_image, data)
            except SearchError:
                raise
            except Exception as error:
                log_failure("image_encoder_failed", error)
                raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Image encoder unavailable") from None
            return await dependency_call(store.image, vector, params, encoder.fingerprint)

        # ponytail: one slot covers upload/parsing/native work; replicas need a shared admission limit.
        native = asyncio.create_task(process())
        try:
            return await asyncio.shield(native)
        finally:
            def release(completed=None):
                if completed is not None and not completed.cancelled():
                    completed.exception()  # Consume a late native failure after client cancellation.
                app.state.image_busy = False
            if not native.done():
                native.add_done_callback(release)
            else:
                release(native)

    @app.get(HEALTH_PATH)
    async def health():
        text = image = "unavailable"
        try:
            _, meta = await asyncio.to_thread(store.meta)
            text = "ready"
            if encoder.ready:
                image_threshold(meta, encoder.fingerprint)
                image = "ready"
        except Exception as error:
            log_failure("search_health_degraded", error)
        return {"status": "UP", "text": text, "image": image,
                "synchronization": "ready" if workers and workers.synchronized and not workers.dead_letters and workers.image_lag_ms == 0 else "degraded",
                "llm": "configured" if settings.llm_configured else "degraded"}

    return app


async def read_upload(request):
    if request.headers.get("content-encoding", "identity") != "identity":
        raise SearchError(400, "INVALID_IMAGE", "Compressed uploads are unsupported")
    if not request.headers.get("content-type", "").lower().startswith("multipart/form-data;"):
        raise SearchError(400, "INVALID_IMAGE", "Use multipart/form-data")
    declared = request.headers.get("content-length")
    if declared and (len(declared) > 19 or not declared.isdecimal() or int(declared) > MAX_BODY):
        raise image_too_large()
    body = bytearray()
    try:
        async with asyncio.timeout(UPLOAD_TIMEOUT_SECONDS):
            async for chunk in request.stream():
                if len(body) + len(chunk) > MAX_BODY:
                    raise image_too_large()
                body.extend(chunk)
    except TimeoutError:
        raise SearchError(400, "INVALID_IMAGE", "Upload deadline exceeded") from None
    payload = bytes(body)
    del body

    async def receive():
        nonlocal payload
        chunk, payload = payload, b""
        return {"type": "http.request", "body": chunk, "more_body": False}

    # Public Request/receive API; the shielded owner always finishes and closes form spools.
    buffered = Request(request.scope, receive=receive)
    try:
        async with buffered.form(max_files=1, max_fields=0, max_part_size=MAX_FILE) as form:
            entries = list(form.multi_items())
            if len(entries) != 1 or entries[0][0] != "file" or not isinstance(entries[0][1], UploadFile):
                raise SearchError(400, "INVALID_IMAGE", "Exactly one file is required")
            data = await entries[0][1].read(MAX_FILE + 1)
            if len(data) > MAX_FILE:
                raise image_too_large()
            return data
    except (HTTPException, MultipartParseError):
        raise SearchError(400, "INVALID_IMAGE", "Invalid multipart image") from None


app = create_app()
