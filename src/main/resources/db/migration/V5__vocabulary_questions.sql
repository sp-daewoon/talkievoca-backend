create table vocabulary_questions (
    word text not null references vocabulary_entries(word) on delete cascade,
    question varchar(300) not null,
    primary key (word, question)
);
