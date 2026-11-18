create unique index if not exists uq_charge_order_active_device_gun
    on pep_domain.charge_order (device_id, gun_no)
    where status in ('PENDING', 'CHARGING');
