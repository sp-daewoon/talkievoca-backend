import asyncio
import os
import subprocess
import sys
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from huggingface_hub import snapshot_download

sys.path.insert(0, "/app")

from app.services.asr.qwenasr_rust import QwenASRRustBackend


MODEL_ID = os.getenv("QWEN3_ASR_MODEL", "Qwen/Qwen3-ASR-0.6B")
MODEL_NAME = "qwen3-asr-0.6b"
MAX_AUDIO_BYTES = int(os.getenv("MAX_AUDIO_BYTES", "2097152"))
API_KEY = os.getenv("API_KEY", "").strip()
engine: QwenASRRustBackend | None = None
inference_lock = asyncio.Lock()


def authorize(authorization: str | None) -> None:
    if API_KEY and authorization != f"Bearer {API_KEY}":
        raise HTTPException(status_code=401, detail="Invalid authentication")


@asynccontextmanager
async def lifespan(_: FastAPI):
    global engine
    snapshot_download(MODEL_ID, local_files_only=os.getenv("HF_HUB_OFFLINE") == "1")
    engine = QwenASRRustBackend(MODEL_ID, num_threads=0, verbosity=0)
    try:
        yield
    finally:
        engine.close()
        engine = None


app = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None)


@app.get("/health")
def health(authorization: Annotated[str | None, Header()] = None):
    authorize(authorization)
    return {"status": "ok"}


@app.get("/v1/models")
def models(authorization: Annotated[str | None, Header()] = None):
    authorize(authorization)
    return {"object": "list", "data": [{"id": MODEL_NAME, "object": "model", "owned_by": "qwen3-asr"}]}


@app.post("/v1/audio/transcriptions")
async def transcribe(
    file: Annotated[UploadFile, File()],
    model: Annotated[str, Form()] = MODEL_NAME,
    language: Annotated[str | None, Form()] = None,
    response_format: Annotated[str, Form()] = "json",
    enable_speaker_diarization: Annotated[bool, Form()] = False,
    authorization: Annotated[str | None, Header()] = None,
):
    authorize(authorization)
    if model.lower() not in {MODEL_NAME, MODEL_ID.lower()}:
        raise HTTPException(status_code=422, detail="Unsupported model")
    if response_format not in {"json", "verbose_json"}:
        raise HTTPException(status_code=422, detail="Unsupported response format")
    if enable_speaker_diarization:
        raise HTTPException(status_code=422, detail="Speaker diarization is not supported")

    audio = await file.read(MAX_AUDIO_BYTES + 1)
    if not audio or len(audio) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=422, detail="Audio must be between 1 byte and 2 MB")

    suffix = Path(file.filename or "recording.m4a").suffix[:10] or ".audio"
    with tempfile.TemporaryDirectory(prefix="talkievoca-asr-") as directory:
        source = Path(directory) / f"input{suffix}"
        normalized = Path(directory) / "normalized.wav"
        source.write_bytes(audio)
        try:
            subprocess.run(
                [
                    "ffmpeg", "-v", "error", "-y", "-i", str(source),
                    "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(normalized),
                ],
                check=True,
                capture_output=True,
                timeout=10,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            raise HTTPException(status_code=422, detail="Audio could not be decoded")

        if engine is None:
            raise HTTPException(status_code=503, detail="ASR is not ready")
        async with inference_lock:
            text = await asyncio.to_thread(engine.transcribe_file, str(normalized), language)

    transcript = text.strip()
    if not transcript:
        raise HTTPException(status_code=422, detail="No speech was recognized")
    return {"text": transcript}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000, log_level=os.getenv("LOG_LEVEL", "info").lower())
