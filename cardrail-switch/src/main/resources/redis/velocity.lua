-- Rolling-window velocity check for one card and currency, executed atomically by Redis.
-- KEYS[1]  sorted set; member = "<attemptId>:<amountMinor>", score = attempt time in epoch millis
-- ARGV[1]  now (epoch millis)      ARGV[2]  window (millis)
-- ARGV[3]  this attempt's amount   ARGV[4]  max attempts in window
-- ARGV[5]  max amount in window    ARGV[6]  member for this attempt
-- Returns {verdict, count, amount}: verdict 0 = recorded, 1 = count limit hit, 2 = amount limit hit.
-- Attempts are only added while within limits, so the set never exceeds the count limit and the
-- scan below is bounded by it.
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local amount = tonumber(ARGV[3])

redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)

local members = redis.call('ZRANGE', key, 0, -1)
local count = #members
local total = 0
for _, member in ipairs(members) do
    total = total + tonumber(string.match(member, ':(%d+)$'))
end

if count + 1 > tonumber(ARGV[4]) then
    return {1, count, total}
end
if total + amount > tonumber(ARGV[5]) then
    return {2, count, total}
end

redis.call('ZADD', key, now, ARGV[6])
redis.call('PEXPIRE', key, window)
return {0, count + 1, total + amount}
