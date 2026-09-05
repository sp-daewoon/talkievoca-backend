CREATE TABLE vocabulary_entries (
    word TEXT PRIMARY KEY,
    meaning_ko TEXT NOT NULL,
    example_en TEXT NOT NULL,
    example_ko TEXT NOT NULL,
    audio_path TEXT NOT NULL
);

CREATE TABLE vocabulary_aliases (
    alias TEXT PRIMARY KEY,
    word TEXT NOT NULL REFERENCES vocabulary_entries(word) ON DELETE CASCADE
);

INSERT INTO vocabulary_entries (word, meaning_ko, example_en, example_ko, audio_path) VALUES
    ('apple', '사과', 'I ate an apple.', '나는 사과를 먹었다.', '/audio/apple.m4a'),
    ('green', '초록색, 녹색', 'The leaves are green.', '나뭇잎은 초록색이다.', '/audio/green.m4a');

INSERT INTO vocabulary_aliases (alias, word) VALUES
    ('apple', 'apple'),
    ('애플', 'apple'),
    ('사과', 'apple'),
    ('green', 'green'),
    ('그린', 'green'),
    ('초록', 'green'),
    ('초록색', 'green'),
    ('녹색', 'green');
