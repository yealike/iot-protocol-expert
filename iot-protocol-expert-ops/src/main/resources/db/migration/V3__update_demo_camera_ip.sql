-- 仅更新仍使用原始演示地址的摄像机，保留用户配置过的地址。
update device_tag t
set tag_value = '127.0.0.1', updated_at = now()
from device_instance d
where t.device_id = d.id
  and d.device_code in ('DEV-CAM-IN', 'DEV-CAM-OUT')
  and t.tag_key = 'ipAddress'
  and t.tag_value = '100.101.91.38';
