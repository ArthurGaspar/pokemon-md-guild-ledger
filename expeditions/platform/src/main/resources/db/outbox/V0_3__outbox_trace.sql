-- The trace an event was written in, so the relay can publish it as part of the same trace.
alter table outbox add column trace_parent varchar(64);
