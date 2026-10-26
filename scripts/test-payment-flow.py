"""Real Order/Payment/Product + PostgreSQL, RabbitMQ, MongoDB race regression.

Requires Docker, Java 17 and built service jars. Only Cart, User and PayOS HTTP
are stubbed. All resources have random names/ports; existing databases are never
used. Run from any directory: python scripts/test-payment-flow.py --build.
Logs and result.json remain in the printed temporary directory for inspection.
"""

import argparse
import base64
import concurrent.futures
import contextlib
import hashlib
import hmac
import json
import os
import re
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
SECRET = secrets.token_hex(32)
CHECKSUM = secrets.token_hex(24)
BUYER = "01JBQ9YDX7K3M8N5P2R4T6V8W0"
PRODUCT = "01JBQ9YDX7K3M8N5P2R4T6V8W1"
VARIANT = "01JBQ9YDX7K3M8N5P2R4T6V8W2"
SHOP = "01JBQ9YDX7K3M8N5P2R4T6V8W3"
ADDRESS = "01JBQ9YDX7K3M8N5P2R4T6V8W4"


def run(*args):
    return subprocess.check_output(args, text=True, encoding="utf-8", stderr=subprocess.STDOUT).strip()


def wait_for(label, check, timeout=90):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            value = check()
            if value:
                return value
        except (URLError, OSError, subprocess.SubprocessError, AssertionError) as error:
            last = error
        time.sleep(0.3)
    raise AssertionError(f"Timed out: {label}; last={last}")


def http(method, url, body=None, token=None, expected=200, headers=None):
    head = dict(headers or {})
    if token:
        head["Authorization"] = "Bearer " + token
    if body is not None:
        head["Content-Type"] = "application/json"
    request = Request(url, data=None if body is None else json.dumps(body).encode(), headers=head, method=method)
    try:
        with urlopen(request, timeout=15) as response:
            status, data = response.status, response.read()
    except HTTPError as error:
        status, data = error.code, error.read()
    allowed = expected if isinstance(expected, tuple) else (expected,)
    assert status in allowed, f"{method} {url}: {status}, expected {expected}; {data[:1000]!r}"
    return json.loads(data) if data else None


def sign(data):
    text = "&".join(f"{key}={data[key]}" for key in sorted(data))
    return hmac.new(CHECKSUM.encode(), text.encode(), hashlib.sha256).hexdigest()


def jwt(role, subject=BUYER):
    def enc(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(b"=")
    content = enc({"alg": "HS256"}) + b"." + enc({"iss": "auth-service", "sub": subject,
                "roles": [role], "iat": int(time.time()), "exp": int(time.time()) + 3600})
    return (content + b"." + base64.urlsafe_b64encode(hmac.new(SECRET.encode(), content, hashlib.sha256).digest()).rstrip(b"=")).decode()


class Dependencies(BaseHTTPRequestHandler):
    """These boundary stubs never implement Order, Payment or Inventory logic."""
    links = {}

    def log_message(self, *_):
        pass

    def reply(self, data, status=200):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        if data is not None:
            self.wfile.write(json.dumps(data).encode())

    def do_GET(self):
        if self.path == "/api/cart":
            item = dict(productId=PRODUCT, variantId=VARIANT, shopId=SHOP, quantity=1, unitPrice=3000, lineTotal=3000)
            self.reply(dict(userId=BUYER, groups=[dict(shopId=SHOP, items=[item], subtotal=3000)], totalQuantity=1, totalAmount=3000))
        elif "/addresses/" in self.path:
            self.reply(dict(id=ADDRESS, recipientName="Race Test", phone="0900000000", province="Da Nang",
                            district="Hai Chau", ward="Test", streetAddress="Fixture address"))
        elif self.path.startswith("/v2/payment-requests/"):
            data = self.links[int(self.path.rsplit("/", 1)[1])]
            self.reply(dict(code="00", data=data, signature=sign(data)))
        else:
            self.reply({}, 404)

    def do_DELETE(self):
        self.reply(None, 204)

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.endswith("/cancel"):
            code = int(self.path.split("/")[-2])
            if self.links[code]["status"] != "PAID":
                self.links[code]["status"] = "CANCELLED"
            self.reply(dict(code="00"))
        else:
            code = body["orderCode"]
            data = self.links.setdefault(code, dict(orderCode=code, amount=body["amount"],
                        paymentLinkId=f"flow-{code}", status="PENDING", checkoutUrl=f"http://localhost/pay/{code}", qrCode="fixture"))
            self.reply(dict(code="00", data=data, signature=sign(data)))


class Suite:
    def __init__(self):
        self.names = []
        self.processes = []
        self.files = []
        self.output = Path(tempfile.mkdtemp(prefix="vmarket-payment-flow-"))
        self.prefix = "vm-flow-" + uuid.uuid4().hex[:10]
        self.token = jwt("BUYER")
        self.admin = jwt("ADMIN", "admin-race")
        self.passed = []
        self.server = None
        properties = run("java", "-XshowSettings:properties", "-version")
        java_home = re.search(r"java.home = (.+)", properties).group(1).strip()
        self.java = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java"))

    def container(self, suffix, image, ports, args=(), command=()):
        name = self.prefix + "-" + suffix
        self.names.append(name)
        publish = [part for port in ports for part in ("-p", f"127.0.0.1::{port}")]
        run("docker", "run", "-d", "--rm", "--name", name, *publish, *args, image, *command)
        mapped = {port: int(run("docker", "port", name, f"{port}/tcp").split(":")[-1]) for port in ports}
        return name, mapped

    def sql(self, database, query):
        return run("docker", "exec", self.pg, "psql", "-v", "ON_ERROR_STOP=1", "-At", "-U", "postgres", "-d", database, "-c", query)

    def mongo(self, expression):
        return run("docker", "exec", self.mg, "mongosh", "--quiet", "vmarket_product", "--eval", expression)

    def rabbit(self, method, path, body=None, expected=200):
        auth = base64.b64encode(b"guest:guest").decode()
        return http(method, self.rabbit_url + "/api" + path, body, expected=expected,
                    headers={"Authorization": "Basic " + auth})

    def publish(self, event_type, payload, event_id=None):
        envelope = dict(eventId=event_id or str(uuid.uuid4()), eventType=event_type,
                        timestamp=int(time.time() * 1000), payload=payload)
        result = self.rabbit("POST", "/exchanges/%2F/vmarket.events/publish", dict(
            properties={"delivery_mode": 2, "content_type": "application/json"}, routing_key=event_type,
            payload=json.dumps(envelope), payload_encoding="string"))
        assert result["routed"]

    def drained(self):
        # Query the broker itself: management HTTP queue statistics can lag several seconds.
        output = run("docker", "exec", self.rb, "rabbitmqctl", "list_queues", "-q", "name",
                     "messages_ready", "messages_unacknowledged")
        rows = [line.split() for line in output.splitlines()]
        rows = [row for row in rows if len(row) == 3 and row[1].isdigit() and row[2].isdigit()]
        return len(rows) >= 6 and all(row[1:] == ["0", "0"] for row in rows if row[0] != "flow-audit")

    def start_service(self, name, options):
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        jar = ROOT / "services" / name / "target" / f"{name}-0.0.1-SNAPSHOT.jar"
        assert jar.is_file(), f"Build first: missing {jar}"
        log = (self.output / f"{name}.log").open("w", encoding="utf-8")
        self.files.append(log)
        props = {"server.port": port, "auth.jwt.secret": SECRET, "app.internal-api-key": SECRET,
                 "spring.rabbitmq.port": self.rp[5672], "spring.rabbitmq.publisher-confirm-type": "correlated",
                 "spring.rabbitmq.publisher-returns": "true", "spring.jpa.show-sql": "false",
                 "spring.datasource.hikari.maximum-pool-size": 4, "app.outbox.delay": 200,
                 "app.outbox.poll-interval-ms": 200, "app.outbox.monitor-delay": 500, **options}
        env = {**os.environ, "INTERNAL_API_KEY": SECRET, "AUTH_JWT_SECRET": SECRET}
        process = subprocess.Popen([self.java, "-Xms64m", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-jar", str(jar),
                                    *(f"--{key}={value}" for key, value in props.items())], env=env, stdout=log, stderr=subprocess.STDOUT)
        self.processes.append(process)
        url = f"http://127.0.0.1:{port}"
        wait_for(name + " health", lambda: http("GET", url + "/actuator/health")["status"] == "UP", 150)
        return url

    def setup(self):
        run("docker", "info", "--format", "{{.ServerVersion}}")
        self.pg, pp = self.container("pg", "postgres:16-alpine", [5432], ("-e", "POSTGRES_PASSWORD=fixture-only"))
        self.mg, mp = self.container("mongo", "mongo:7", [27017], command=("--replSet", "rs0", "--bind_ip_all"))
        self.rb, self.rp = self.container("rabbit", "rabbitmq:3-management-alpine", [5672, 15672],
                        ("-e", "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2 +A 4"))
        self.rabbit_url = f"http://127.0.0.1:{self.rp[15672]}"
        wait_for("postgres", lambda: self.sql("postgres", "select 1"))
        for db in ("vmarket_order", "vmarket_payment"):
            self.sql("postgres", f"create database {db}")
        wait_for("mongo", lambda: self.mongo("db.adminCommand({ping:1}).ok"))
        self.mongo("rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]})")
        wait_for("replica primary", lambda: self.mongo("db.hello().isWritablePrimary") == "true")
        wait_for("rabbit", lambda: self.rabbit("GET", "/overview"))
        self.rabbit("PUT", "/exchanges/%2F/vmarket.events", dict(type="topic", durable=True, auto_delete=False, arguments={}), 201)
        self.rabbit("PUT", "/queues/%2F/flow-audit", dict(durable=True, auto_delete=False, arguments={}), 201)
        self.rabbit("POST", "/bindings/%2F/e/vmarket.events/q/flow-audit", dict(routing_key="#", arguments={}), 201)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Dependencies)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        stub = f"http://127.0.0.1:{self.server.server_port}"
        datasource = dict(**{"spring.datasource.url": f"jdbc:postgresql://127.0.0.1:{pp[5432]}/vmarket_order",
            "spring.datasource.username": "postgres", "spring.datasource.password": "fixture-only"})
        self.order = self.start_service("order-service", {**datasource, "app.cart-service.base-url": stub,
                                        "app.cart-service.internal-api-key": SECRET, "app.user-service.base-url": stub})
        datasource["spring.datasource.url"] = datasource["spring.datasource.url"].replace("vmarket_order", "vmarket_payment")
        self.payment = self.start_service("payment-service", {**datasource, "app.order-service.base-url": self.order,
            "app.payos.enabled": "true", "app.payos.client-id": "fixture", "app.payos.api-key": "fixture",
            "app.payos.checksum-key": CHECKSUM, "app.payos.base-url": stub})
        self.product = self.start_service("product-service", {
            "spring.mongodb.uri": f"mongodb://127.0.0.1:{mp[27017]}/vmarket_product?replicaSet=rs0&directConnection=true",
            "app.catalog-migration.enabled": "false"})
        self.mongo("db.products.insertOne(" + json.dumps(dict(_id=PRODUCT, shopId=SHOP, sellerId=BUYER, name="Fixture",
            categoryId="fixture", categoryVisible=True, categoryPath=["fixture"], status="ACTIVE", version=0,
            minPrice=3000, maxPrice=3000, availableStock=20, soldCount=0,
            variants=[dict(_id=VARIANT, sku="fixture", attributes={}, price=3000, currency="VND", stock=20,
                           reservedStock=0, soldCount=0, deleted=False)])) + ")")

    def create(self, method="PAYOS"):
        order = http("POST", self.order + "/api/orders", dict(addressId=ADDRESS, paymentMethod=method), self.token, 201)
        oid = order["id"]
        wait_for("stock reserved", lambda: http("GET", self.order + "/api/orders/" + oid, token=self.token)["stockReserved"])
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            results = list(pool.map(lambda _: http("POST", self.payment + "/api/payments", dict(orderId=oid), self.token), range(8)))
        ids = {p["id"] for p in results}
        assert len(ids) == 1, results
        return oid, ids.pop()

    def send_webhook(self, pid):
        Dependencies.links[pid]["status"] = "PAID"
        data = dict(orderCode=pid, amount=3000, currency="VND", paymentLinkId=f"flow-{pid}",
                    reference=f"bank-{pid}", code="00", transactionDateTime=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        body = dict(data=data, signature=sign(data))
        with concurrent.futures.ThreadPoolExecutor(4) as pool:
            list(pool.map(lambda _: http("POST", self.payment + "/api/payments/webhooks/payos", body), range(4)))

    @contextlib.contextmanager
    def cancellation_barrier(self, oid):
        """Hold the exact row cancellation locks, allowing webhook to commit first."""
        process = subprocess.Popen(["docker", "exec", "-i", self.pg, "psql", "-v", "ON_ERROR_STOP=1", "-At",
                                   "-U", "postgres", "-d", "vmarket_payment"], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        try:
            process.stdin.write(f"begin; select order_id from payment_order_states where order_id='{oid}' for update;\n\\echo BARRIER_READY\n")
            process.stdin.flush()
            ready = threading.Event()
            def consume():
                for line in process.stdout:
                    if "BARRIER_READY" in line:
                        ready.set()
                        return
            threading.Thread(target=consume, daemon=True).start()
            assert ready.wait(15), "Postgres cancellation barrier failed"
            yield
        finally:
            if process.poll() is None:
                process.communicate("commit;\n\\q\n", timeout=15)

    def assert_final(self, oid, pid):
        wait_for("one refund", lambda: self.sql("vmarket_payment", f"select count(*) from refund_requests where payment_id={pid}") == "1")
        assert http("GET", self.order + "/api/orders/" + oid, token=self.token)["status"] == "CANCELLED"
        assert http("GET", self.payment + f"/api/payments/{pid}", token=self.token)["status"] == "SUCCEEDED"
        wait_for("inventory released", lambda: self.mongo(f"db.inventory_reservations.findOne({{orderId:'{oid}'}}).status") == "RELEASED")
        variant = json.loads(self.mongo("var v=db.products.findOne({_id:'" + PRODUCT + "'}).variants[0];"
                            "JSON.stringify({stock:Number(v.stock),reservedStock:Number(v.reservedStock),soldCount:Number(v.soldCount)})"))
        assert (variant["stock"], variant["reservedStock"], variant["soldCount"]) == (20, 0, 0), variant
        for _ in range(2):
            self.publish("PaymentSucceeded", dict(orderId=oid))
            self.publish("OrderStatusChanged", dict(orderId=oid, previousStatus="CANCELLED", status="CANCELLED", paymentMethod="PAYOS"))
        # Observe committed work and ACKs, not a fixed sleep, before checking duplicates.
        wait_for("event consumers drained", self.drained)
        assert self.sql("vmarket_payment", f"select count(*) from refund_requests where payment_id={pid}") == "1"
        assert http("GET", self.order + "/api/orders/" + oid, token=self.token)["status"] == "CANCELLED"
        assert self.mongo(f"db.inventory_reservations.findOne({{orderId:'{oid}'}}).status") == "RELEASED"

    def races(self):
        for first in ("webhook", "cancellation"):
            oid, pid = self.create()
            if first == "webhook":
                with self.cancellation_barrier(oid):
                    http("PUT", self.order + f"/api/orders/{oid}/cancel", token=self.token)
                    wait_for("cancellation blocked at barrier", lambda: int(self.sql("vmarket_payment",
                        "select count(*) from pg_stat_activity where datname='vmarket_payment' and wait_event_type='Lock'")) > 0)
                    self.send_webhook(pid)
                    assert self.sql("vmarket_payment", f"select count(*) from refund_requests where payment_id={pid}") == "0"
            else:
                gate = threading.Event()
                with concurrent.futures.ThreadPoolExecutor(1) as pool:
                    pending = pool.submit(lambda: (gate.wait(30) or (_ for _ in ()).throw(AssertionError("barrier timed out")), self.send_webhook(pid)))
                    http("PUT", self.order + f"/api/orders/{oid}/cancel", token=self.token)
                    wait_for("cancellation committed before webhook", lambda: http("GET", self.payment + f"/api/payments/{pid}", token=self.token)["status"] == "CANCELLED")
                    gate.set()
                    pending.result(timeout=30)
            self.assert_final(oid, pid)
            self.passed.append(f"{first} first; 8 concurrent creates, 4 webhook replays, duplicate broker events, final order/payment/inventory/refund")
        refunds = http("GET", self.payment + "/api/payments/admin/refunds", token=self.admin)
        rid = refunds[0]["id"]
        url = self.payment + f"/api/payments/admin/refunds/{rid}/confirm"
        http("PUT", url, dict(transferReference="manual-fixture"), self.token, 403)
        confirmed = http("PUT", url, dict(transferReference="manual-fixture"), self.admin)
        assert confirmed["confirmedBy"] == "admin-race" and confirmed["status"] == "COMPLETED" and confirmed["completedAt"]
        http("PUT", url, dict(transferReference="manual-fixture"), self.admin)
        http("PUT", url, dict(transferReference="different"), self.admin, 409)
        self.passed.append("manual refund requires Admin; confirmation audit and idempotency")
        self.recovery(oid)

    def recovery(self, oid):
        first, second = str(uuid.uuid4()), str(uuid.uuid4())
        payload = json.dumps(dict(orderId=oid, previousStatus="CANCELLED", status="CANCELLED", paymentMethod="PAYOS"))
        self.sql("vmarket_order", "insert into event_outbox (id,event_type,payload,created_at,aggregate_id,state,retry_attempts) values "
                 f"('{first}','OrderStatusChanged','{payload}',current_timestamp - interval '1 minute','{oid}','DEAD',10),"
                 f"('{second}','OrderStatusChanged','{payload}',current_timestamp,'{oid}','PENDING',0)")
        status_url = self.order + "/api/orders/admin/outbox/status"
        status = http("GET", status_url, token=self.admin)
        assert status["dead"] == 1 and status["blocked"] == 1, status
        http("POST", self.order + f"/api/orders/admin/outbox/{first}/replay", dict(reason="binding repaired"), self.token, 403)
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            replays = list(pool.map(lambda _: http("POST", self.order + f"/api/orders/admin/outbox/{first}/replay",
                                dict(reason="binding repaired"), self.admin, (204, 409, 404)), range(2)))
        # The dispatcher may ACK/delete before the losing Admin request acquires its lock (404).
        assert replays.count(None) == 1, replays
        wait_for("Postgres replay and successor ACK", lambda: self.sql("vmarket_order",
            f"select count(*) from event_outbox where id in ('{first}','{second}')") == "0")
        assert self.sql("vmarket_order", f"select replayed_by || ':' || previous_attempts from outbox_replay_audit where event_id='{first}'") == "admin-race:10"
        status = http("GET", status_url, token=self.admin)
        assert status["dead"] == 0 and status["blocked"] == 0, status
        self.passed.append("PostgreSQL DEAD recovery: successors blocked until Admin replay, persisted audit, ACK deletes both in order")

    def close(self):
        for process in self.processes:
            process.terminate()
        for process in self.processes:
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        for file in self.files:
            file.close()
        if self.server:
            self.server.shutdown()
            self.server.server_close()
        for name in self.names:
            subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)

    def diagnostics(self):
        for name in self.names:
            try:
                (self.output / (name + ".log")).write_text(run("docker", "logs", name), encoding="utf-8")
            except subprocess.SubprocessError:
                pass
        if hasattr(self, "rabbit_url"):
            try:
                events = self.rabbit("POST", "/queues/%2F/flow-audit/get", dict(count=100, ackmode="ack_requeue_true", encoding="auto"))
                (self.output / "events.json").write_text(json.dumps(events, indent=2), encoding="utf-8")
            except (AssertionError, OSError):
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true")
    args = parser.parse_args()
    if args.build:
        wrapper = "mvnw.cmd" if os.name == "nt" else "./mvnw"
        subprocess.run([wrapper, "-B", "--no-transfer-progress", "-pl", "order-service,payment-service,product-service", "-am", "package", "-DskipTests"], cwd=ROOT / "services", check=True)
    suite = Suite()
    print(f"Payment flow logs: {suite.output}", flush=True)
    result = dict(passed=suite.passed, success=False, dependencies="real PostgreSQL/RabbitMQ/MongoDB; Cart/User/PayOS HTTP stubs")
    try:
        suite.setup()
        suite.races()
        result["success"] = True
        print(json.dumps(result, indent=2), flush=True)
    except Exception as error:
        result["error"] = str(error)
        suite.diagnostics()
        raise
    finally:
        suite.close()
        (suite.output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
