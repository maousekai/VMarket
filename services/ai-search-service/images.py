"""CPU encoder and fixed-origin MinIO reads. No request-time downloads."""
import hashlib
import io
import json
import re
import threading
import time
import warnings
from contextlib import closing
from pathlib import Path
from urllib.parse import urlsplit

from PIL import Image, ImageOps, UnidentifiedImageError

from contract import EMBEDDING_DIMS, MAX_IMAGE_PIXELS, MAX_RESIZED_PIXELS, RESIZE_SHORT_EDGE
from schemas import MAX_FILE, SearchError, image_too_large

Image.MAX_IMAGE_PIXELS = MAX_IMAGE_PIXELS
WEIGHTS_URL = "https://download.pytorch.org/models/mobilenet_v3_small-047dcff4.pth"


def object_key(url, origin, bucket):
    parsed, trusted = urlsplit(url), urlsplit(origin)
    if (parsed.scheme != trusted.scheme or parsed.netloc != trusted.netloc or parsed.username
            or parsed.password or parsed.query or parsed.fragment or trusted.path not in {"", "/"}):
        raise ValueError("Image origin rejected")
    match = re.fullmatch(rf"/{re.escape(bucket)}/(products/[A-Za-z0-9_-]+\.(?:jpg|jpeg|png|webp))", parsed.path)
    if not match:
        raise ValueError("Image key rejected")
    return match[1]


def decoded_image(data):
    if len(data) > MAX_FILE:
        raise image_too_large()
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(data)) as image:
                if image.format not in {"JPEG", "PNG", "WEBP"} or getattr(image, "n_frames", 1) != 1:
                    raise ValueError("Unsupported image")
                if image.width * image.height > MAX_IMAGE_PIXELS or not image.width or not image.height:
                    raise ValueError("Image dimensions exceeded")
                short, long = sorted(image.size)
                # Match short-edge Resize rounding before any decoded/resized pixels are allocated.
                if RESIZE_SHORT_EDGE * (RESIZE_SHORT_EDGE * long // short) > MAX_RESIZED_PIXELS:
                    raise ValueError("Resized image dimensions exceeded")
                image.verify()
            image = Image.open(io.BytesIO(data))
            try:
                ImageOps.exif_transpose(image, in_place=True)
                if image.mode == "RGB":
                    return image  # Transfer ownership; avoid another full-resolution RGB copy.
                converted = image.convert("RGB")
                image.close()
                return converted
            except BaseException:
                image.close()
                raise
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise SearchError(400, "INVALID_IMAGE", "Use one static valid JPEG, PNG or WebP image") from None


class ImageEncoder:
    def __init__(self, settings):
        self.settings = settings
        self.ready = False
        self.fingerprint = None
        self.lock = threading.Lock()

    def load(self):
        import torch
        from torchvision.models import MobileNet_V3_Small_Weights, mobilenet_v3_small
        from torchvision import transforms
        path = Path(self.settings.weights)
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        checksum_path = path.with_suffix(path.suffix + ".sha256")
        expected = self.settings.weights_sha256 or (checksum_path.read_text().strip() if checksum_path.exists() else "")
        if not re.fullmatch(r"[a-f0-9]{64}", expected) or digest != expected or not digest.startswith("047dcff4"):
            raise ValueError("Official verified weights required; run manage.py weights")
        torch.set_num_threads(1)
        torch.set_num_interop_threads(1)
        model = mobilenet_v3_small(weights=None)
        model.load_state_dict(torch.load(path, map_location="cpu", weights_only=True))
        self.model = torch.nn.Sequential(model.features, model.avgpool, torch.nn.Flatten(1)).eval()
        preset = MobileNet_V3_Small_Weights.IMAGENET1K_V1.transforms()
        self.transform = transforms.Compose([
            transforms.Resize([RESIZE_SHORT_EDGE], interpolation=preset.interpolation, antialias=preset.antialias),
            transforms.CenterCrop(preset.crop_size), transforms.ToTensor(),
            transforms.Normalize(mean=preset.mean, std=preset.std)])
        self.torch = torch
        spec = {"sha256": digest, "pooling": "features-avgpool-flatten", "dims": EMBEDDING_DIMS, "dtype": "fp32",
                "resize": [RESIZE_SHORT_EDGE], "crop": preset.crop_size, "mean": preset.mean, "std": preset.std,
                "interpolation": str(preset.interpolation), "antialias": preset.antialias, "exif": "transpose-rgb-v1"}
        self.fingerprint = hashlib.sha256(json.dumps(spec, sort_keys=True).encode()).hexdigest()
        self.ready = True

    def embed_image(self, data):
        if not self.ready:
            raise SearchError(503, "IMAGE_SEARCH_UNAVAILABLE", "Image encoder unavailable")
        with self.lock:
            image = decoded_image(data)
            try:
                tensor = self.transform(image).unsqueeze(0)
            finally:
                image.close()
            with self.torch.inference_mode():
                vector = self.model(tensor).squeeze(0)
                norm = vector.norm()
                if vector.numel() != EMBEDDING_DIMS or not self.torch.isfinite(vector).all() or norm <= 0:
                    raise ValueError("Invalid embedding")
                return (vector / norm).tolist()

    def embed_catalog(self, url):
        s = self.settings
        key = object_key(url, s.minio_origin, s.bucket)
        client = s3_client(s, s.minio_key, s.minio_secret)
        started = time.monotonic()
        try:
            response = client.get_object(Bucket=s.bucket, Key=key)
            with closing(response["Body"]) as body:
                if response["ContentLength"] > MAX_FILE:
                    raise image_too_large()
                data = bytearray()
                for chunk in body.iter_chunks(65536):
                    data.extend(chunk)
                    if len(data) > MAX_FILE:
                        raise image_too_large()
                    if time.monotonic() - started > s.image_download_timeout:
                        raise ValueError("Image download exceeded bounds")
            return self.embed_image(bytes(data))
        finally:
            client.close()


def s3_client(settings, access_key, secret_key):
    import boto3
    from botocore.config import Config
    if not access_key or not secret_key:
        raise ValueError("Explicit MinIO credentials required")
    return boto3.client("s3", endpoint_url=settings.minio_endpoint, aws_access_key_id=access_key,
        aws_secret_access_key=secret_key, region_name="us-east-1",
        config=Config(connect_timeout=settings.minio_timeout, read_timeout=settings.minio_timeout,
                      retries={"total_max_attempts": 1}, proxies={}, s3={"addressing_style": "path"}))
