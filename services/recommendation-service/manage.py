"""Run Flyway or seed catalog snapshots during a paused-writer maintenance window."""
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
        state = channel.queue_declare(queue=store.settings.queue, passive=True).method
        if state.consumer_count:
            raise RuntimeError("Stop the recommendation consumer before seeding")
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
    parser.add_argument("command", choices=["migrate", "seed"])
    parser.add_argument("--writers-paused", action="store_true")
    parser.add_argument("--consumer-stopped", action="store_true")
    args = parser.parse_args()
    settings = Settings.load()
    if args.command == "migrate":
        env = {**os.environ, "FLYWAY_URL": f"jdbc:postgresql://{settings.db_host}:{settings.db_port}/{settings.db_name}",
               "FLYWAY_USER": settings.db_user, "FLYWAY_PASSWORD": settings.db_password}
        wrapper = str(ROOT.parent / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
        subprocess.run([wrapper, "-f", "recommendation-service/pom.xml", "flyway:migrate"],
                       cwd=ROOT.parent, env=env, check=True)
    else:
        if not args.writers_paused or not args.consumer_stopped:
            parser.error("seed requires --writers-paused --consumer-stopped")
        if not settings.internal_key:
            parser.error("Set INTERNAL_API_KEY")
        store = Store(settings)
        try:
            store.start()
            print(f"Seeded {seed(store)} snapshots; start the consumer to drain queued catalog events.")
        finally:
            store.close()


if __name__ == "__main__":
    main()
