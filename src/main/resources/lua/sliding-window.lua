-- 滑动窗口限流 Lua 脚本
-- KEYS[1] = 限流 key (如 zhiwei:ratelimit:user:user-001)
-- ARGV[1] = 窗口大小（毫秒，如 60000 = 1分钟）
-- ARGV[2] = 最大请求数
-- ARGV[3] = 当前时间戳（毫秒）
-- 返回: 1=允许, 0=拒绝

local key = KEYS[1]
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 移除窗口外的请求
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 当前窗口内的请求数
local count = redis.call('ZCARD', key)

if count < limit then
    -- 允许：添加当前请求
    redis.call('ZADD', key, now, now .. '-' .. math.random(100000))
    redis.call('PEXPIRE', key, window)
    return 1
else
    -- 拒绝
    return 0
end