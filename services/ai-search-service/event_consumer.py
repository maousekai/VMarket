"""Heartbeat-safe Product invalidations. ACK follows durable metadata/image intent."""
import json
import logging
import os
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from settings import ROOT  # Load service .env before reading queue configuration.

import httpx
import pika
from elasticsearch import ApiError, ConnectionError, ConnectionTimeout

EVENT_TYPES = ("ProductCreated", "ProductUpdated", "ProductDeleted")
EXCHANGE = os.getenv("EVENT_EXCHANGE", "vmarket.events")
QUEUE = os.getenv("EVENT_QUEUE", "ai-search.events.v2")


def connection_parameters():
    return pika.ConnectionParameters(host=os.getenv("RABBITMQ_HOST", "localhost"),
        port=int(os.getenv("RABBITMQ_PORT", "5672")),
        credentials=pika.PlainCredentials(os.getenv("RABBITMQ_USERNAME", "guest"), os.getenv("RABBITMQ_PASSWORD", "guest")),
        heartbeat=30, blocked_connection_timeout=30, socket_timeout=2, stack_timeout=5)


def declare_topology(channel):
    channel.exchange_declare(exchange=EXCHANGE, exchange_type="topic", durable=True)
    channel.exchange_declare(exchange=EXCHANGE + ".dlx", exchange_type="topic", durable=True)
    channel.queue_declare(queue=QUEUE, durable=True, arguments={"x-dead-letter-exchange": EXCHANGE + ".dlx",
                          "x-dead-letter-routing-key": QUEUE + ".dead"})
    channel.queue_declare(queue=QUEUE + ".dead", durable=True)
    channel.queue_bind(queue=QUEUE + ".dead", exchange=EXCHANGE + ".dlx", routing_key=QUEUE + ".dead")
    for event_type in EVENT_TYPES:
        channel.queue_bind(queue=QUEUE, exchange=EXCHANGE, routing_key=event_type)


def validate_event(body, routing_key):
    if len(body) > 256 * 1024:
        raise ValueError("Event too large")
    envelope = json.loads(body)
    if not isinstance(envelope, dict) or envelope.get("eventType") != routing_key or routing_key not in EVENT_TYPES:
        raise ValueError("Event type mismatch")
    uuid.UUID(envelope["eventId"])
    stamp = envelope["timestamp"]
    if type(stamp) is not int or not 0 <= stamp <= 2**63 - 1:
        raise ValueError("Invalid event timestamp")
    product_id = envelope["payload"]["productId"]
    if not isinstance(product_id, str) or product_id in {".", ".."} or not 1 <= len(product_id) <= 128 or any(ord(c) < 32 for c in product_id):
        raise ValueError("Invalid product ID")
    return product_id, stamp


def transient(exc):
    return isinstance(exc, (httpx.TransportError, ConnectionError, ConnectionTimeout)) or (
        isinstance(exc, (ApiError, httpx.HTTPStatusError)) and
        (exc.status_code if isinstance(exc, ApiError) else exc.response.status_code) in {429, 500, 502, 503, 504})


class Workers:
    def __init__(self, store, encoder):
        self.store, self.encoder = store, encoder
        self.stop = threading.Event()
        self.synchronized = False
        self.metadata_lag_ms = self.image_lag_ms = None
        self.dead_letters = 0
        self.active = None
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="metadata")
        self.threads = []

    def handle(self, body, key):
        product_id, stamp = validate_event(body, key)
        for attempt in range(3):
            try:
                snapshot = self.store.fetch_snapshot(product_id)
                _, meta = self.store.meta()
                self.store.apply_snapshot(snapshot, stamp, meta.get("encoderFingerprint"))
                self.metadata_lag_ms = max(0, int(time.time() * 1000) - stamp)
                logging.info("metadata_sync lag_ms=%s", self.metadata_lag_ms)
                return
            except Exception as exc:
                if not transient(exc) or attempt == 2:
                    raise
                if self.stop.wait(2**attempt):
                    raise RuntimeError("Stopping")

    def consume(self):
        while not self.stop.is_set():
            connection = None
            try:
                connection = pika.BlockingConnection(connection_parameters())
                channel = connection.channel()
                declare_topology(channel)
                self.store.meta()
                if self.active is not None and not self.active.done():
                    raise RuntimeError("Previous delivery still finishing")
                channel.basic_qos(prefetch_count=1)

                def on_message(ch, method, properties, body):
                    key = method.routing_key
                    if method.exchange == "" and key == QUEUE:
                        key = (properties.headers or {}).get("x-search-event-type")
                    future = self.executor.submit(self.handle, body, key)
                    self.active = future
                    # Capture this connection: reconnect must never ACK the previous channel's tag.
                    owner = connection

                    def finish(completed):
                        def acknowledge():
                            if not ch.is_open:
                                return
                            if completed.exception() is None:
                                ch.basic_ack(method.delivery_tag)
                            else:
                                logging.warning("product_event_failed code=SYNC_FAILED")
                                ch.basic_nack(method.delivery_tag, requeue=False)
                        if owner.is_open:
                            try:
                                owner.add_callback_threadsafe(acknowledge)
                            except pika.exceptions.ConnectionWrongStateError:
                                pass
                    future.add_done_callback(finish)

                channel.basic_consume(queue=QUEUE, on_message_callback=on_message)
                self.synchronized = True
                next_check = 0
                while not self.stop.is_set() and connection.is_open:
                    connection.process_data_events(time_limit=0.5)
                    if time.monotonic() >= next_check:
                        self.dead_letters = channel.queue_declare(queue=QUEUE + ".dead", passive=True).method.message_count
                        next_check = time.monotonic() + 5
            except Exception:
                self.synchronized = False
                logging.warning("consumer_unavailable")
                self.stop.wait(5)
            finally:
                if connection and connection.is_open:
                    connection.close()
                self.synchronized = False

    def images(self):
        try:
            self.encoder.load()
        except Exception:
            logging.warning("encoder_unavailable code=MODEL_LOAD_FAILED")
        while not self.stop.is_set():
            try:
                self.store.process_image_job(self.encoder)
                result = self.store.client.search(index=self.store.target, body={"size": 0,
                    "query": {"terms": {"imageState": ["pending", "failed"]}},
                    "aggs": {"oldest": {"min": {"field": "eventAt"}}}})
                oldest = result["aggregations"]["oldest"]["value"]
                self.image_lag_ms = max(0, int(time.time() * 1000 - oldest)) if oldest is not None else 0
                self.stop.wait(1)
            except Exception:
                logging.warning("image_worker_unavailable")
                self.stop.wait(2)

    def start(self):
        for function in (self.consume, self.images):
            thread = threading.Thread(target=function, daemon=True, name=function.__name__)
            thread.start()
            self.threads.append(thread)

    def close(self):
        self.stop.set()
        for thread in self.threads:
            thread.join(timeout=5)
        self.executor.shutdown(wait=True, cancel_futures=True)
