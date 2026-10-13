"""Existing durable queue; ACK follows PostgreSQL commit, work leaves pika's I/O thread."""
import json
import logging
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

import httpx
import pika
import psycopg
from psycopg_pool import PoolTimeout

from schemas import valid_id

EVENT_TYPES = ("ProductCreated", "ProductUpdated", "ProductDeleted", "OrderPlaced", "OrderStatusChanged")


def connection_parameters(settings):
    return pika.ConnectionParameters(host=settings.rabbit_host, port=settings.rabbit_port,
        credentials=pika.PlainCredentials(settings.rabbit_user, settings.rabbit_password),
        heartbeat=30, blocked_connection_timeout=5, socket_timeout=settings.timeout,
        stack_timeout=settings.timeout + 3, connection_attempts=1)


def declare_topology(channel, settings):
    exchange, queue = settings.exchange, settings.queue
    channel.exchange_declare(exchange=exchange, exchange_type="topic", durable=True)
    channel.exchange_declare(exchange=exchange + ".dlx", exchange_type="topic", durable=True)
    channel.queue_declare(queue=queue, durable=True, arguments={
        "x-dead-letter-exchange": exchange + ".dlx", "x-dead-letter-routing-key": queue + ".dead"})
    channel.queue_declare(queue=queue + ".dead", durable=True)
    channel.queue_bind(queue=queue + ".dead", exchange=exchange + ".dlx", routing_key=queue + ".dead")
    for event_type in EVENT_TYPES:
        channel.queue_bind(queue=queue, exchange=exchange, routing_key=event_type)


def validate_event(body, key):
    if len(body) > 256 * 1024:
        raise ValueError("Event too large")
    data = json.loads(body)
    if not isinstance(data, dict) or key not in EVENT_TYPES or data.get("eventType") != key:
        raise ValueError("Event type mismatch")
    event_id = str(uuid.UUID(data["eventId"]))
    if type(data["timestamp"]) is not int or not 0 <= data["timestamp"] <= 2**63 - 1:
        raise ValueError("Invalid event timestamp")
    payload = data["payload"]
    if not isinstance(payload, dict):
        raise ValueError("Invalid payload")
    if key.startswith("Product"):
        if not valid_id(payload.get("productId")):
            raise ValueError("Invalid product ID")
    else:
        if not valid_id(payload.get("orderId")):
            raise ValueError("Invalid order ID")
        if key == "OrderPlaced":
            items = payload.get("items")
            if not isinstance(items, list) or not items or len(items) > 500:
                raise ValueError("Invalid order items")
            for item in items:
                if (not isinstance(item, dict) or not valid_id(item.get("productId"))
                        or type(item.get("quantity")) is not int or not 1 <= item["quantity"] <= 2**31 - 1):
                    raise ValueError("Invalid order item")
        elif not isinstance(payload.get("status"), str) or not payload["status"].strip():
            raise ValueError("Invalid order status")
    return event_id, payload


def transient(error):
    return isinstance(error, (httpx.TransportError, psycopg.OperationalError, PoolTimeout,
                              psycopg.errors.QueryCanceled)) or (
        isinstance(error, httpx.HTTPStatusError) and error.response.status_code in {429, 500, 502, 503, 504})


class Consumer:
    def __init__(self, store):
        self.store = store
        self.stop = threading.Event()
        self.connected = False
        self.connection = None
        self.channel = None
        self.thread = None
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="recommendation-metadata")

    def handle(self, body, key):
        event_id, payload = validate_event(body, key)
        for attempt in range(3):
            if self.stop.is_set():
                raise InterruptedError("Stopping")
            try:
                if self.store.seen(event_id):
                    return
                snapshot = self.store.fetch_snapshot(payload["productId"]) if key.startswith("Product") else None
                self.store.apply(snapshot, event_id)
                return
            except Exception as error:
                if not transient(error) or attempt == 2:
                    raise
                logging.warning("recommendation_event_retry type=%s attempt=%s", type(error).__name__, attempt + 1)
                if self.stop.wait(2**attempt):
                    raise InterruptedError("Stopping") from None

    def on_message(self, channel, method, properties, body):
        owner = self.connection
        future = self.executor.submit(self.handle, body, method.routing_key)

        def completed(task):
            error = task.exception()
            def settle():
                if self.stop.is_set() or not channel.is_open:
                    return
                if error is None:
                    channel.basic_ack(delivery_tag=method.delivery_tag)
                else:
                    logging.warning("recommendation_event_dead_letter type=%s", type(error).__name__)
                    channel.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
            if not self.stop.is_set() and owner.is_open:
                try:
                    owner.add_callback_threadsafe(settle)
                except pika.exceptions.ConnectionWrongStateError:
                    pass
        future.add_done_callback(completed)

    def run(self):
        while not self.stop.is_set():
            try:
                self.connection = pika.BlockingConnection(connection_parameters(self.store.settings))
                self.channel = self.connection.channel()
                declare_topology(self.channel, self.store.settings)
                self.channel.basic_qos(prefetch_count=1)
                self.channel.basic_consume(queue=self.store.settings.queue, on_message_callback=self.on_message)
                self.connected = True
                if not self.stop.is_set():
                    self.channel.start_consuming()
            except Exception as error:
                if not self.stop.is_set():
                    logging.warning("recommendation_broker_disconnected type=%s", type(error).__name__)
            finally:
                self.connected = False
                if self.connection is not None and self.connection.is_open:
                    try:
                        self.connection.close()
                    except pika.exceptions.AMQPError:
                        pass
            self.stop.wait(5)

    def start(self):
        self.thread = threading.Thread(target=self.run, daemon=True, name="recommendation-consumer")
        self.thread.start()

    def close(self):
        self.stop.set()
        if self.connection is not None and self.connection.is_open:
            try:
                self.connection.add_callback_threadsafe(self.channel.stop_consuming)
            except (pika.exceptions.ConnectionWrongStateError, AttributeError):
                pass
        if self.thread is not None:
            self.thread.join()
        self.executor.shutdown(wait=True)
