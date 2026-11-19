-- After a rush: the reservation database must still be consistent. Every check prints ok or FAIL.
--   psql -U guild -d reservation -v exp=<expedition id> -f verify.sql

\echo 'Reservations by status'
select status, count(*) from reservations where expedition_id = :exp group by status order by status;

\echo 'Parties by status'
select status, count(*) from parties where expedition_id = :exp group by status order by status;

\echo 'Invariants'
with active as (
    select * from reservations
    where expedition_id = :exp and status in ('HELD', 'CONFIRMED', 'LOCKED', 'COMPLETED', 'FAILED')
), per_role as (
    select a.party_id, a.role, count(*) as taken,
           case a.role when 'TANK' then e.tanks when 'HEALER' then e.healers else e.damage end as allowed
    from active a join expeditions e on e.id = a.expedition_id
    group by a.party_id, a.role, e.tanks, e.healers, e.damage
)
select 'one place per adventurer' as invariant,
       case when count(*) = 0 then 'ok' else 'FAIL' end as result
from (select adventurer_id from active group by adventurer_id having count(*) > 1) dupes
union all
select 'no party over its composition',
       case when count(*) = 0 then 'ok' else 'FAIL' end
from per_role where taken > allowed
union all
select 'open-slot counter matches the reservations',
       case when e.open_slots = e.parties * (e.tanks + e.healers + e.damage) - (select count(*) from active)
            then 'ok' else 'FAIL' end
from expeditions e where e.id = :exp
union all
select 'locked parties are full and all locked',
       case when count(*) = 0 then 'ok' else 'FAIL' end
from parties p join expeditions e on e.id = p.expedition_id
where p.expedition_id = :exp and p.status in ('LOCKED', 'COMPLETED', 'FAILED')
  and (select count(*) from active a where a.party_id = p.id) <> e.tanks + e.healers + e.damage;
