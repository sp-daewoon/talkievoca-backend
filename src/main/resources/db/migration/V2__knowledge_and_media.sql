CREATE TABLE knowledge_sources (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    extracted_text TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_chunks (
    id UUID PRIMARY KEY,
    source_id UUID NOT NULL REFERENCES knowledge_sources(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    UNIQUE (source_id, ordinal)
);

CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    word TEXT NOT NULL REFERENCES vocabulary_entries(word),
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    storage_path TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX knowledge_chunks_source_id_idx ON knowledge_chunks(source_id);
CREATE INDEX media_assets_word_idx ON media_assets(word);
