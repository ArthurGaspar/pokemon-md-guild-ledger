-- Applies capacity.changed only if it is newer than what we have, so late or repeated deliveries do nothing.
-- KEYS: info     ARGV: openSlots, version
-- Returns 1 when applied, 0 when stale.
local current = tonumber(redis.call('HGET', KEYS[1], 'capacityVersion') or '-1')
if tonumber(ARGV[2]) <= current then
  return 0
end
redis.call('HSET', KEYS[1], 'openSlots', ARGV[1], 'capacityVersion', ARGV[2])
return 1
