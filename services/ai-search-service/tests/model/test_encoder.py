"""Optional real CPU check. No external API calls; weights must already be cached."""
import io
import json
import math
import time
import unittest
from dataclasses import replace
from unittest.mock import Mock

from PIL import Image
from images import ImageEncoder
from settings import Settings
from schemas import MAX_FILE
from pathlib import Path
from fastapi.testclient import TestClient
from main import create_app


class EncoderChecks(unittest.TestCase):
    def test_normalized_embeddings_and_maximum_pixels(self):
        encoder = ImageEncoder(Settings())
        started = time.perf_counter()
        encoder.load()
        load = time.perf_counter() - started
        for side in (256, 5000):
            output = io.BytesIO()
            image = Image.new("RGB", (side, side), "red")
            image.save(output, format="JPEG")
            image.close()
            started = time.perf_counter()
            vector = encoder.embed_image(output.getvalue())
            self.assertEqual(len(vector), 576)
            self.assertTrue(all(math.isfinite(v) for v in vector))
            self.assertAlmostEqual(sum(v * v for v in vector), 1, places=5)
            print(json.dumps({"side": side, "seconds": time.perf_counter() - started, "loadSeconds": load, "cuda": encoder.torch.version.cuda}))
        # Full handler overhead + the largest allowed four-channel decode, under the same cap.
        output = io.BytesIO()
        image = Image.new("RGBA", (5000, 5000), (255, 0, 0, 128))
        image.save(output, format="PNG")
        image.close()
        store = Mock()
        store.image.return_value = {"results": [], "limit": 20, "totalReturned": 0}
        app = create_app(replace(Settings(), llm_enabled=False), store, encoder, run_workers=False)
        with TestClient(app) as client:
            # PNG permits trailing bytes: combine maximum decoded pixels with maximum upload bytes.
            upload = output.getvalue().ljust(MAX_FILE, b"\0")
            response = client.post("/api/ai/search/image", files={"file": ("maximum.png", upload, "image/png")})
            self.assertEqual(response.status_code, 200)
            store.image.assert_called_once()
            self.assertEqual(client.post("/api/ai/search/image", files={"file": ("over.png", upload + b"x")}).status_code, 413)
        peak = Path("/sys/fs/cgroup/memory.peak")
        if peak.exists():
            print(json.dumps({"cgroupPeakBytes": int(peak.read_text())}))
        try:
            import resource
            print(json.dumps({"peakRssBytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024}))
        except ImportError:
            pass


if __name__ == "__main__": unittest.main()
