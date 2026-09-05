alter table vocabulary_entries drop constraint vocabulary_entries_origin_check;

alter table vocabulary_entries
    add constraint vocabulary_entries_origin_check
        check (origin in ('seed', 'upload', 'slm', 'manual'));
