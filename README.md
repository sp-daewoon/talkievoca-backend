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
```

`docker compose down -v` removes all local development data volumes.

For a physical Apple Watch on the same Wi-Fi, configure the Apple app with the Mac's LAN address, for example `http://192.168.x.x:8080`, not `localhost`. The API returns audio URLs using the request host.

The first release stores vocabulary seed data in PostgreSQL, copies it to OpenSearch for retrieval, and serves bundled M4A pronunciation files. Spring AI is present for the next provider/RAG step; no API key is required for this stack.
