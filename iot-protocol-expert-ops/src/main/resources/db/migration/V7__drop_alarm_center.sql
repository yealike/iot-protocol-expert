drop table if exists pep_domain.alarm_record;
drop table if exists pep_domain.alarm_rule;

update pep_agent.ai_skill_component
set description = '按设备、通道与遥测证据排查离线原因'
where component_key = 'device-offline-check'
  and description = '按设备、通道、遥测与告警证据排查离线原因';
