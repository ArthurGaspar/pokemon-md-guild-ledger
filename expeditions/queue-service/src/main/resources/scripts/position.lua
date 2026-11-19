-- Where an adventurer stands, joining the line first when ARGV[3] is "join".
-- KEYS: info, line, seq, admitted     ARGV: adventurer, nowMs, mode
-- Returns {state, a, b, openSlots, activeAdmissions}:
--   state -1 unknown expedition | 0 waiting (a = 0-based rank, b = people in line)
--   state 1 admitted (a = admission expiry ms) | 2 not in line
local info, line, seq, admitted = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local adventurer, now, mode = ARGV[1], tonumber(ARGV[2]), ARGV[3]

if redis.call('HEXISTS', info, 'opensAt') == 0 then
  return {-1, 0, 0, 0, 0}
end

local function state(code, a, b)
  local open = tonumber(redis.call('HGET', info, 'openSlots') or '0')
  local active = redis.call('ZCOUNT', admitted, '(' .. now, '+inf')
  return {code, a, b, open, active}
end

local expiry = redis.call('ZSCORE', admitted, adventurer)
if expiry then
  if tonumber(expiry) > now then
    return state(1, tonumber(expiry), 0)
  end
  -- a lapsed admission: the place is gone, they may line up again at the back
  redis.call('ZREM', admitted, adventurer)
end

local rank = redis.call('ZRANK', line, adventurer)
if not rank and mode == 'join' then
  redis.call('ZADD', line, redis.call('INCR', seq), adventurer)
  rank = redis.call('ZRANK', line, adventurer)
end
if not rank then
  return state(2, 0, 0)
end
return state(0, rank, redis.call('ZCARD', line))
