alter table vocabulary_entries alter column audio_path drop not null;

alter table vocabulary_entries
    add column origin varchar(20) not null default 'seed';

alter table vocabulary_entries
    add column verification_status varchar(20) not null default 'trusted';

alter table vocabulary_entries
    add column created_at timestamp with time zone not null default current_timestamp;

alter table vocabulary_entries
    add constraint vocabulary_entries_origin_check
        check (origin in ('seed', 'upload', 'slm'));

alter table vocabulary_entries
    add constraint vocabulary_entries_verification_check
        check (verification_status in ('trusted', 'pending'));

create index vocabulary_entries_verification_idx
    on vocabulary_entries (verification_status, origin);
