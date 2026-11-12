-- 告警去重：同一条规则在同一设备（同通道）上只能有一条未关闭记录。
--
-- 背景：巡检任务每 5 秒补判一次「持续时间」规则，如果判定用空串而存储用 NULL，
-- 查询匹配不上就会每轮新建一条。代码已改为统一存空串，这里再加一层数据库约束兜底。
--
-- 使用部分唯一索引而不是唯一约束：已关闭的历史记录允许多条，
-- 只有 state = 'OPEN' 的那一条必须唯一。channel_no 用 coalesce 归一，
-- 否则 NULL 在唯一索引里互不相等，等于没约束。

-- 先清理历史重复：保留每组最早的一条
delete from alarm_record a
using alarm_record b
where a.state = 'OPEN'
  and b.state = 'OPEN'
  and a.rule_id = b.rule_id
  and a.device_id = b.device_id
  and coalesce(a.channel_no, '') = coalesce(b.channel_no, '')
  and a.id > b.id;

update alarm_record set channel_no = '' where state = 'OPEN' and channel_no is null;

create unique index uq_alarm_open_scope
    on alarm_record (rule_id, device_id, coalesce(channel_no, ''))
    where state = 'OPEN';