# TalkieVoca Backend

Kotlin + Spring Boot backend for the TalkieVoca Apple Watch app. PostgreSQL owns vocabulary and provenance, OpenSearch retrieves uploaded knowledge, Ollama supplies missing vocabulary cards, and Qwen3-ASR is the optional high-accuracy speech provider.

## Run

```bash
brew services start ollama
ollama pull qwen3:4b
docker compose up --build
```

The default Compose setup runs PostgreSQL, OpenSearch, the Kotlin API, and a pinned ARM64-capable Qwen3-ASR CPU image. A small TalkieVoca adapter loads only Qwen3-ASR-0.6B through the image's Rust backend. The stack reaches Ollama on the Mac through `host.docker.internal`. Known words never call the text model.

The first Qwen start downloads the model into a named Docker volume and can take several minutes. Later starts reuse the cache. The API remains available for text lookup while ASR reports `unavailable` during warm-up.

## Verify

```bash
curl http://localhost:8080/health
curl 'http://localhost:8080/v1/watch/word-feedback?word=apple'
curl 'http://localhost:8080/v1/watch/word-feedback?word=unknown'
curl -X POST http://localhost:8080/v1/assistant/query \
  -H 'Content-Type: application/json' \
  -d '{"text":"사과"}'
curl -F 'file=@src/main/resources/static/audio/apple.m4a' http://localhost:8080/v1/assistant/audio
```

`/health` reports `asr` and `slm` as `ready`, `disabled`, or `unavailable`.

## Qwen3-ASR

Local development uses `quantatrisk/qwen3-asr:cpu-latest` pinned by digest. `docker/qwen-asr-server.py` exposes only the OpenAI-compatible endpoints TalkieVoca needs, converts short uploads to mono 16 kHz WAV, and serializes inference through the image's Qwen Rust engine. It does not load the provider's Chinese realtime, speaker diarization, or forced-alignment models. Raw Watch recordings live only in an automatically removed temporary directory.

For production, replace the local endpoint with an official CUDA/vLLM Qwen3-ASR service using the same OpenAI-compatible contract:

```bash
export TALKIEVOCA_ASR_ENABLED=true
export TALKIEVOCA_ASR_URL=https://your-qwen-asr-server
export TALKIEVOCA_ASR_MODEL=Qwen/Qwen3-ASR-0.6B
export TALKIEVOCA_ASR_API_KEY=replace-with-your-secret
docker compose up --build -d
curl http://localhost:8080/health
```

Do not commit the API key. Set `TALKIEVOCA_ASR_ENABLED=false` to run without Qwen; Siri/native dictation and text lookup continue to work. The Docker model cache is `qwen_asr_huggingface`.

## Resolution Order

1. PostgreSQL exact word and curated aliases.
2. Relevant uploaded PDF chunks from OpenSearch.
3. Local Ollama `qwen3:4b` generation for a missing word.

Generated cards are cached in PostgreSQL with `origin=slm` and `verification_status=pending`. They can answer the same personal user quickly but are excluded from the trusted OpenSearch vocabulary index until reviewed. Raw Watch recordings are forwarded to ASR and never persisted.

## Ingest Content

```bash
curl -F 'file=@/path/to/lesson.pdf' -F 'title=Lesson 1' http://localhost:8080/v1/knowledge/pdf
curl -F 'word=apple' -F 'file=@/path/to/apple.mp3' http://localhost:8080/v1/media/audio
curl -G --data-urlencode 'q=lesson' http://localhost:8080/v1/knowledge/search
```

`docker compose down -v` removes all local development data volumes.

For a physical Apple Watch on the same Wi-Fi, configure the Apple app with the Mac's LAN address, for example `http://192.168.x.x:8080`, not `localhost`. The API returns audio URLs using the request host.

PDF originals and uploaded audio are stored in the Docker `media_data` volume. Their metadata, text, and word connection live in PostgreSQL; extracted PDF chunks are indexed into OpenSearch.

The Apple app uses `POST /v1/assistant/query` for Siri/native dictation and `POST /v1/assistant/audio` for explicit Qwen recording. The older Watch feedback endpoint remains available for compatibility and diagnostics.
