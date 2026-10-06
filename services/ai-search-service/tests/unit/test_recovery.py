import json
import time
import unittest
from concurrent.futures import Future
from types import SimpleNamespace
from unittest.mock import Mock, patch

import httpx
import pika

from event_consumer import SYNC_CHECK_SECONDS, Workers
from main import create_app
from manage import replay_dead
from settings import Settings


VALID_EVENT = json.dumps({"eventId": "d7836ec9-667d-44b9-9279-d21479e83aa5",
    "eventType": "ProductUpdated", "timestamp": 1, "payload": {"productId": "p1"}}).encode()


class RecoveryChecks(unittest.TestCase):
    def test_replay_quarantines_invalid_messages_and_continues_within_limit(self):
        invalid = [b"not-json", b"[]", b'{}', VALID_EVENT.replace(b'"p1"', b'null')]
        properties = pika.BasicProperties(delivery_mode=1, content_type="application/octet-stream",
            message_id="original", expiration="60000", headers={"trace": "keep"})
        channel = Mock()
        channel.basic_get.side_effect = [
            (SimpleNamespace(delivery_tag=tag), properties, body)
            for tag, body in enumerate(invalid + [VALID_EVENT], 1)]
        confirmed = []
        channel.basic_publish.side_effect = lambda **values: confirmed.append(values)
        channel.basic_ack.side_effect = lambda tag: self.assertEqual(len(confirmed), tag)
        connection = Mock()
        connection.__enter__ = Mock(return_value=connection)
        connection.__exit__ = Mock(return_value=False)
        connection.channel.return_value = channel
        with patch("manage.pika.BlockingConnection", return_value=connection):
            replay_dead(Settings(), len(invalid) + 1)
        self.assertEqual(channel.basic_get.call_count, len(invalid) + 1)
        channel.confirm_delivery.assert_called_once()
        channel.queue_declare.assert_any_call(queue=Settings().queue + ".quarantine", durable=True)
        for publication, body in zip(confirmed, invalid + [VALID_EVENT]):
            self.assertEqual(publication["body"], body)
            self.assertEqual(publication["exchange"], "")
            self.assertTrue(publication["mandatory"])
            outgoing = publication["properties"]
            self.assertEqual((outgoing.delivery_mode, outgoing.message_id, outgoing.headers["trace"]), (2, "original", "keep"))
        self.assertEqual([p["routing_key"] for p in confirmed],
            [Settings().queue + ".quarantine"] * len(invalid) + [Settings().queue])
        self.assertEqual(confirmed[-1]["properties"].headers["x-search-event-type"], "ProductUpdated")
        self.assertEqual(confirmed[-1]["properties"].expiration, "60000")
        for publication in confirmed[:-1]:
            self.assertIsNone(publication["properties"].expiration)
            self.assertEqual(publication["properties"].headers["x-search-original-expiration"], "60000")
        self.assertEqual(properties.headers, {"trace": "keep"})
        self.assertEqual(properties.delivery_mode, 1)

    def test_unconfirmed_replay_or_quarantine_keeps_original_unacked(self):
        for body in (b"invalid", VALID_EVENT):
            with self.subTest(body=body):
                channel = Mock()
                channel.basic_get.return_value = (SimpleNamespace(delivery_tag=1), pika.BasicProperties(), body)
                channel.basic_publish.side_effect = pika.exceptions.NackError([])
                connection = Mock()
                connection.__enter__ = Mock(return_value=connection)
                connection.__exit__ = Mock(return_value=False)
                connection.channel.return_value = channel
                with patch("manage.pika.BlockingConnection", return_value=connection), self.assertRaises(pika.exceptions.NackError):
                    replay_dead(Settings(), 2)
                channel.basic_ack.assert_not_called()
                self.assertEqual(channel.basic_get.call_count, 1)

    def test_delivery_stays_in_flight_until_ack_and_new_broker_check(self):
        store = Mock(settings=Settings())
        workers = Workers(store, Mock())
        workers.image_lag_ms = 0
        channel = Mock(is_open=True)
        channel.queue_declare.return_value = SimpleNamespace(method=SimpleNamespace(message_count=0))
        connection = Mock(is_open=True)
        connection.channel.return_value = channel
        callbacks = []
        connection.add_callback_threadsafe.side_effect = callbacks.append
        future = Future()
        observed = []

        def process(**_):
            try:
                observed.append(workers.synchronization_ready)
                callback = channel.basic_consume.call_args.kwargs["on_message_callback"]
                callback(channel, SimpleNamespace(routing_key="ProductUpdated", exchange="events", delivery_tag=1),
                    pika.BasicProperties(), VALID_EVENT)
                observed.extend([workers.metadata_in_flight, workers.synchronization_ready])
                workers.metadata_lag_ms = 0  # Even a zero-lag completed future still awaits broker ACK.
                future.set_result(None)
                workers.check_synchronization(channel)
                observed.append(workers.synchronization_ready)
                callbacks.pop()()
                observed.extend([workers.metadata_in_flight, workers.synchronization_ready])
                workers.check_synchronization(channel)
                observed.append(workers.synchronization_ready)
            finally:
                workers.stop.set()

        connection.process_data_events.side_effect = process
        try:
            with patch("event_consumer.pika.BlockingConnection", return_value=connection), \
                    patch.object(workers.executor, "submit", return_value=future):
                workers.consume()
            self.assertEqual(observed, [True, 1, False, False, 0, False, True])
            channel.basic_ack.assert_called_once_with(1)
            self.assertFalse(workers.synchronization_ready)
        finally:
            workers.close()


class HealthChecks(unittest.IsolatedAsyncioTestCase):
    async def test_health_requires_fresh_drained_metadata_and_no_failures(self):
        store = Mock(settings=Settings())
        store.meta.return_value = ("index", {})
        workers = Workers(store, Mock())
        workers.image_lag_ms = 0
        counts = {Settings().queue: 3}
        channel = Mock()
        channel.queue_declare.side_effect = lambda queue, **_: SimpleNamespace(
            method=SimpleNamespace(message_count=counts.get(queue, 0)))
        with patch("main.Workers", return_value=workers):
            app = create_app(Settings(), store, Mock(ready=False))
        try:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as api:
                async def state(expected):
                    self.assertEqual((await api.get("/api/ai/search/health")).json()["synchronization"], expected)
                await state("degraded")  # Startup has not checked the broker.
                workers.check_synchronization(channel)
                await state("degraded")  # Backlog after downtime, without any image jobs.
                counts[Settings().queue] = 0
                workers.metadata_in_flight = 1
                workers.metadata_lag_ms = 2000
                workers.check_synchronization(channel)
                await state("degraded")
                workers.metadata_in_flight = 0
                workers.check_synchronization(channel)
                await state("ready")
                for attribute, value in (("metadata_lag_ms", 1), ("dead_letters", 1), ("quarantined", 1),
                                         ("image_lag_ms", None), ("synchronized", False),
                                         ("metadata_checked_at", time.monotonic() - SYNC_CHECK_SECONDS - 1)):
                    previous = getattr(workers, attribute)
                    setattr(workers, attribute, value)
                    await state("degraded")
                    setattr(workers, attribute, previous)
                counts[Settings().queue + ".quarantine"] = 1
                workers.check_synchronization(channel)
                await state("degraded")
        finally:
            workers.close()
            await app.state.expander.client.aclose()


if __name__ == "__main__":
    unittest.main()
