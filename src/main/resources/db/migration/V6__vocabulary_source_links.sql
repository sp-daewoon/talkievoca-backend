create table vocabulary_sources (
    word text not null references vocabulary_entries(word) on delete cascade,
    source_id uuid not null references knowledge_sources(id) on delete cascade,
    primary key (word, source_id)
);
