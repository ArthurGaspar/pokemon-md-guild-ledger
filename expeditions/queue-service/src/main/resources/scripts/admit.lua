-- Lets the front of the line through, never more than there are open slots.
-- KEYS: info, line, admitted     ARGV: nowMs, batch, admissionTtlMs
-- Returns the adventurers admitted by this call.
local info, line, admitted = KEYS[1], KEYS[2], KEYS[3]
local now, batch, ttl = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3])

local opensAt = redis.call('HGET', info, 'opensAt')
if not opensAt or now < tonumber(opensAt) then
  return {}
end

redis.call('ZREMRANGEBYSCORE', admitted, '-inf', now)
local open = tonumber(redis.call('HGET', info, 'openSlots') or '0')
local room = math.min(open - redis.call('ZCARD', admitted), batch)
if room <= 0 then
  return {}
end

local popped = redis.call('ZPOPMIN', line, room)
local through = {}
for i = 1, #popped, 2 do
  redis.call('ZADD', admitted, now + ttl, popped[i])
  through[#through + 1] = popped[i]
end
return through
