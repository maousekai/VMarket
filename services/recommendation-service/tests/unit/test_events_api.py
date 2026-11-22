import json
import time
import unittest
from unittest.mock import Mock, patch

import jwt
import pika
from fastapi.testclient import TestClient

from event_consumer import validate_event
from event_publisher import Publisher, behavior_event
from main import BASE, create_app
from tests.fixtures import settings, snapshot
from tests.unit.test_api import MemoryStore


class ViewEventApiTest(unittest.TestCase):
    def setUp(self):
        self.store = MemoryStore([snapshot("seed")])
        self.publisher = Mock()
        self.client = TestClient(create_app(self.store.settings, self.store, run_consumer=False,
                                            publisher=self.publisher))
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)
        self.publisher.close.assert_called_once()

    def auth(self, **claims):
        payload = {"iss": "auth-service", "sub": "buyer", "exp": int(time.time()) + 60, **claims}
        return {"Authorization": "Bearer " + jwt.encode(payload, self.store.settings.jwt_secret, algorithm="HS256")}

    def test_signed_in_view_is_published_for_the_token_subject(self):
        response = self.client.post(BASE + "/events", json={"productId": "seed", "action": "view"}, headers=self.auth())
        self.assertEqual(response.status_code, 202)
        event = self.publisher.publish.call_args.args[0]
        self.assertEqual(event["eventType"], "UserBehaviorTracked")
        self.assertEqual(event["payload"], {"userId": "buyer", "productId": "seed", "action": "view"})
        # The published envelope passes the consumer's own validation.
        _, payload, _ = validate_event(json.dumps(event).encode(), "UserBehaviorTracked")
        self.assertEqual(payload["userId"], "buyer")

    def test_guests_and_bad_tokens_are_rejected(self):
        body = {"productId": "seed", "action": "view"}
        self.assertEqual(self.client.post(BASE + "/events", json=body).status_code, 401)
        self.assertEqual(self.client.post(BASE + "/events", json=body, headers=self.auth(exp=1)).status_code, 401)
        self.assertEqual(self.client.post(BASE + "/events", json=body,
                                          headers={"X-User-Id": "forged"}).status_code, 401)
        self.publisher.publish.assert_not_called()

    def test_clients_cannot_send_other_actions_or_identities(self):
        for body in ({"productId": "seed", "action": "purchase"}, {"productId": "seed", "action": "add_to_cart"},
                     {"productId": "seed", "action": "view", "userId": "someone-else"},
                     {"productId": " ", "action": "view"}, {"productId": "a/b", "action": "view"},
                     {"productId": 1, "action": "view"}, {"action": "view"}):
            response = self.client.post(BASE + "/events", json=body, headers=self.auth())
            self.assertEqual(response.status_code, 400, body)
        self.publisher.publish.assert_not_called()

    def test_broker_failure_is_reported_without_details(self):
        self.publisher.publish.side_effect = pika.exceptions.AMQPConnectionError("secret host detail")
        response = self.client.post(BASE + "/events", json={"productId": "seed", "action": "view"}, headers=self.auth())
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json()["error"]["code"], "EVENT_UNAVAILABLE")
        self.assertNotIn("secret", response.text)


class PublisherTest(unittest.TestCase):
    def test_envelope_shape(self):
        event = behavior_event("u", "p", "view", now=1.5)
        self.assertEqual(event["timestamp"], 1500)
        self.assertEqual(event["eventType"], "UserBehaviorTracked")

    def test_reconnects_once_after_a_dropped_connection(self):
        publisher = Publisher(settings())
        channels = [Mock(is_open=True), Mock(is_open=True)]
        channels[0].basic_publish.side_effect = pika.exceptions.StreamLostError()
        connections = [Mock(is_open=True, **{"channel.return_value": c}) for c in channels]
        with patch("event_publisher.pika.BlockingConnection", side_effect=connections):
            publisher.publish(behavior_event("u", "p", "view"))
        channels[1].basic_publish.assert_called_once()
        self.assertEqual(channels[1].basic_publish.call_args.kwargs["routing_key"], "UserBehaviorTracked")
        connections[0].close.assert_called_once()

    def test_gives_up_after_the_retry(self):
        publisher = Publisher(settings())
        with patch("event_publisher.pika.BlockingConnection", side_effect=pika.exceptions.AMQPConnectionError()):
            with self.assertRaises(pika.exceptions.AMQPError):
                publisher.publish(behavior_event("u", "p", "view"))


if __name__ == "__main__":
    unittest.main()
