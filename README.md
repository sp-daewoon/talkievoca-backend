# TalkieVoca Backend

Kotlin + Spring Boot + Spring AI backend for TalkieVoca.

## Run

```bash
docker compose up --build
```

## Verify

```bash
curl http://localhost:8080/health
curl 'http://localhost:8080/v1/watch/word-feedback?word=apple'
curl 'http://localhost:8080/v1/watch/word-feedback?word=unknown'
curl -X POST http://localhost:8080/v1/assistant/query \
  -H 'Content-Type: application/json' \
  -d '{"text":"사과"}'
```

## Ingest Content

```bash
curl -F 'file=@/path/to/lesson.pdf' -F 'title=Lesson 1' http://localhost:8080/v1/knowledge/pdf
curl -F 'word=apple' -F 'file=@/path/to/apple.mp3' http://localhost:8080/v1/media/audio
curl -G --data-urlencode 'q=lesson' http://localhost:8080/v1/knowledge/search
```

`docker compose down -v` removes all local development data volumes.

For a physical Apple Watch on the same Wi-Fi, configure the Apple app with the Mac's LAN address, for example `http://192.168.x.x:8080`, not `localhost`. The API returns audio URLs using the request host.

PDF originals and uploaded audio are stored in the Docker `media_data` volume. Their metadata, text, and word connection live in PostgreSQL; extracted PDF chunks are indexed into OpenSearch. Spring AI is present for the next embedding/provider step; no API key is required for this stack.

The Apple app uses `POST /v1/assistant/query` as its single runtime entry point. The older Watch feedback endpoint remains available for compatibility and diagnostics.
