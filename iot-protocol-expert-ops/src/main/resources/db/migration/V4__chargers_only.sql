-- Remove device classes outside the charging-pile scope.
-- Foreign keys to orders and bills intentionally prevent deleting financial history.
update device_instance set parent_id = null
where parent_id in (select id from device_instance where device_type <> 'CHARGER');
delete from alarm_record where rule_id in (
    select id from alarm_rule where product_id in (select id from product where device_type <> 'CHARGER')
       or device_id in (select id from device_instance where device_type <> 'CHARGER')
);
delete from alarm_rule where product_id in (select id from product where device_type <> 'CHARGER');
delete from device_property_ts where device_id in (select id from device_instance where device_type <> 'CHARGER');
delete from device_instance where device_type <> 'CHARGER';
delete from product where device_type <> 'CHARGER';
