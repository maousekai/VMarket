"""Publishes UserBehaviorTracked for signed-in product views (FR-REC-03).

The browser never talks to RabbitMQ: it calls POST /events with its access token, and this
service, which already holds broker credentials, puts the event on the shared exchange. The
event then follows the same consumer path as behaviour from Cart and Order.
"""
import json
import threading
import time
import uuid

import pika

from event_consumer import connection_parameters

EVENT_TYPE = "UserBehaviorTracked"


def behavior_event(user_id, product_id, action, *, now=None):
    return {"eventId": str(uuid.uuid4()), "eventType": EVENT_TYPE,
            "timestamp": int((time.time() if now is None else now) * 1000),
            "payload": {"userId": user_id, "productId": product_id, "action": action}}


class Publisher:
    def __init__(self, settings):
        self.settings = settings
        self.lock = threading.Lock()
        self.connection = None
        self.channel = None

    def _open(self):
        self.connection = pika.BlockingConnection(connection_parameters(self.settings))
        self.channel = self.connection.channel()
        self.channel.exchange_declare(exchange=self.settings.exchange, exchange_type="topic", durable=True)
        # Confirms make a broker refusal surface as an error instead of a silent drop.
        self.channel.confirm_delivery()

    def _reset(self):
        connection, self.connection, self.channel = self.connection, None, None
        if connection is not None and connection.is_open:
            try:
                connection.close()
            except pika.exceptions.AMQPError:
                pass

    def publish(self, event):
        body = json.dumps(event, separators=(",", ":")).encode()
        properties = pika.BasicProperties(content_type="application/json", delivery_mode=2)
        with self.lock:
            # One retry covers a connection the broker dropped while idle (missed heartbeats).
            for attempt in range(2):
                try:
                    if self.channel is None or not self.channel.is_open:
                        self._reset()
                        self._open()
                    self.channel.basic_publish(exchange=self.settings.exchange, routing_key=EVENT_TYPE,
                                               body=body, properties=properties)
                    return
                except (pika.exceptions.AMQPError, OSError):
                    self._reset()
                    if attempt == 1:
                        raise

    def close(self):
        with self.lock:
            self._reset()
