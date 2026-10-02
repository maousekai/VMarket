import asyncio
import logging
import tempfile
from pathlib import Path
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from python_multipart.exceptions import MultipartParseError

from event_consumer import Workers
from images import ImageEncoder
from llm import Expander
from schemas import MAX_BODY, MAX_FILE, SearchError, error_response, parse_query
from search import SearchStore
from settings import Settings
from settings import ROOT

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("elastic_transport.transport").setLevel(logging.WARNING)
logging.getLogger("pika").setLevel(logging.WARNING)


def create_app(settings=None, store=None, encoder=None, expander=None, run_workers=True):
    settings = settings or Settings()
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
        except Exception:
            raise SearchError(503, "SEARCH_UNAVAILABLE", "Search dependency unavailable") from None

    @app.get("/api/ai/search")
    async def keyword_search(request: Request):
        params = parse_query(request.query_params, "keyword")
        terms = await expander.expand_query(params["keyword"])
        return await dependency_call(store.keyword, params, terms)

    @app.get("/api/ai/search/suggestions")
    async def suggestions(request: Request):
        return await dependency_call(store.suggestions, parse_query(request.query_params, "suggestions"))

    @app.post("/api/ai/search/image")
    async def image_search(request: Request):
        params = parse_query(request.query_params, "image")
        if app.state.image_busy:
            raise SearchError(429, "SEARCH_BUSY", "Another image request is running")
        app.state.image_busy = True
        native = None
        try:
            if not encoder.ready:
                raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Image encoder unavailable")
            if request.headers.get("content-encoding", "identity") != "identity":
                raise SearchError(400, "INVALID_IMAGE", "Compressed uploads are unsupported")
            if not request.headers.get("content-type", "").lower().startswith("multipart/form-data;"):
                raise SearchError(400, "INVALID_IMAGE", "Use multipart/form-data")
            declared = request.headers.get("content-length")
            if declared and (len(declared) > 19 or not declared.isdecimal() or int(declared) > MAX_BODY):
                raise SearchError(413, "IMAGE_TOO_LARGE", "Multipart body exceeds limit")
            # Bound bytes before parsing: cancellation cannot leave partially parsed temporary files.
            body = bytearray()
            async with asyncio.timeout(10):
                async for chunk in request.stream():
                    if len(body) + len(chunk) > MAX_BODY:
                        raise SearchError(413, "IMAGE_TOO_LARGE", "Multipart body exceeds limit")
                    body.extend(chunk)
            request._body = bytes(body)
            del body
            try:
                async with request.form(max_files=1, max_fields=0, max_part_size=MAX_FILE) as form:
                    entries = list(form.multi_items())
                    if len(entries) != 1 or entries[0][0] != "file" or not isinstance(entries[0][1], UploadFile):
                        raise SearchError(400, "INVALID_IMAGE", "Exactly one file is required")
                    data = await entries[0][1].read(MAX_FILE + 1)
                    if len(data) > MAX_FILE:
                        raise SearchError(413, "IMAGE_TOO_LARGE", "Image exceeds 10000000 bytes")
            except (HTTPException, MultipartParseError):
                raise SearchError(400, "INVALID_IMAGE", "Invalid multipart image") from None
            request._body = b""
            native = asyncio.create_task(asyncio.to_thread(encoder.embed_image, data))
            try:
                vector = await asyncio.shield(native)
            except SearchError:
                raise
            except Exception:
                raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Image encoder unavailable") from None
            return await dependency_call(store.image, vector, params, encoder.fingerprint)
        except TimeoutError:
            raise SearchError(400, "INVALID_IMAGE", "Upload deadline exceeded") from None
        finally:
            def release(completed=None):
                if completed is not None and not completed.cancelled():
                    completed.exception()  # Consume a late native failure after client cancellation.
                app.state.image_busy = False
            if native is not None and not native.done():
                native.add_done_callback(release)
            else:
                release(native)

    @app.get("/api/ai/search/health")
    async def health():
        text = image = "unavailable"
        try:
            _, meta = await asyncio.to_thread(store.meta)
            text = "ready"
            calibration = meta.get("calibration", {})
            if encoder.ready and meta.get("encoderFingerprint") == encoder.fingerprint and calibration.get("fingerprint") == encoder.fingerprint and calibration.get("validated"):
                image = "ready"
        except Exception:
            pass
        return {"status": "UP", "text": text, "image": image,
                "synchronization": "ready" if workers and workers.synchronized and not workers.dead_letters and workers.image_lag_ms == 0 else "degraded",
                "llm": "configured" if settings.llm_configured else "degraded"}

    return app


app = create_app()
