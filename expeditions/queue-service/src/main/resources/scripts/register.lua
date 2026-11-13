-- Records an expedition from expedition.published. Capacity is only a starting value (version 0): reservation's
-- capacity.changed, which may already have arrived, wins.
-- KEYS: info     ARGV: opensAtMs, totalSlots
redis.call('HSET', KEYS[1], 'opensAt', ARGV[1])
redis.call('HSETNX', KEYS[1], 'openSlots', ARGV[2])
redis.call('HSETNX', KEYS[1], 'capacityVersion', '0')
return 1
