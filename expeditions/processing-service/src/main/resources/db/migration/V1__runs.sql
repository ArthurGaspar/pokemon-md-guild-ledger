-- One row per locked party sent into the dungeon. The id is also the idempotency key for the simulator.
create table runs (
    id              varchar(36)              primary key,
    expedition_id   bigint                   not null,
    party_id        bigint                   not null,
    members         text                     not null,
    status          varchar(16)              not null,
    attempts        int                      not null,
    next_attempt_at timestamp with time zone not null,
    lease_until     timestamp with time zone,
    deadline        timestamp with time zone not null,
    last_error      varchar(300),
    failure         varchar(16),
    floors_cleared  int,
    poke_found      int,
    created_at      timestamp with time zone not null,
    finished_at     timestamp with time zone,
    trace_parent    varchar(64),
    version         bigint                   not null
);

create index runs_due on runs (status, next_attempt_at);
create index runs_by_party on runs (expedition_id, party_id);
