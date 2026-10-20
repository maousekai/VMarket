"""Run Flyway, prune 30-day catalog receipts, or seed snapshots while Product writers are paused."""
import argparse
import os
import subprocess

import pika

from event_consumer import connection_parameters, declare_topology
from schemas import Snapshot
from settings import ROOT, Settings
from store import Store


def seed(store):
    connection = pika.BlockingConnection(connection_parameters(store.settings))
    try:
        channel = connection.channel()
        declare_topology(channel, store.settings)
        count, page = 0, 0
        while True:
            response = store.client.get("/api/products/internal/search-snapshots", params={"page": page, "size": 100})
            response.raise_for_status()
            data = response.json()
            if (not isinstance(data, dict) or data.get("page") != page
                    or type(data.get("hasMore")) is not bool or not isinstance(data.get("items"), list)
                    or len(data["items"]) > 100 or (data["hasMore"] and not data["items"])):
                raise ValueError("Invalid snapshot export page")
            for item in data["items"]:
                store.apply(Snapshot.model_validate(item))
                count += 1
            if not data["hasMore"]:
                return count
            page += 1
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["migrate", "seed", "cleanup"])
    parser.add_argument("--writers-paused", action="store_true")
    args = parser.parse_args()
    settings = Settings.load()
    if args.command == "migrate":
        env = {**os.environ, "FLYWAY_URL": f"jdbc:postgresql://{settings.db_host}:{settings.db_port}/{settings.db_name}",
               "FLYWAY_USER": settings.db_user, "FLYWAY_PASSWORD": settings.db_password}
        wrapper = str(ROOT.parent / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
        subprocess.run([wrapper, "-f", "recommendation-service/pom.xml", "flyway:migrate"],
                       cwd=ROOT.parent, env=env, check=True)
    else:
        if args.command == "seed" and not args.writers_paused:
            parser.error("seed requires --writers-paused: Product export uses offset pagination")
        if args.command == "seed" and not settings.internal_key:
            parser.error("Set INTERNAL_API_KEY")
        store = Store(settings)
        try:
            store.start()
            if args.command == "cleanup":
                print(f"Removed {store.prune_receipts()} catalog receipts older than 30 days; behavior history/receipts retained.")
            else:
                print(f"Seeded {seed(store)} snapshots; keep the consumer running to drain queued catalog events.")
        finally:
            store.close()


if __name__ == "__main__":
    main()
