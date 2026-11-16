-- Events already handled, for consumers that need deduplication (see platform/outbox/Inbox).
create table inbox (
    event_id     varchar(36)              primary key,
    processed_at timestamp with time zone not null
);
