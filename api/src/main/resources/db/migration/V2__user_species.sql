-- Members are Pokémon too: each account has a species, chosen at registration and editable later.
alter table users add column species_id integer not null default 25;
