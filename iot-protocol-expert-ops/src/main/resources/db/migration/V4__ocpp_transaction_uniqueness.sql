create unique index if not exists uq_charge_order_device_ocpp_transaction
    on pep_domain.charge_order (device_id, ocpp_transaction_id)
    where ocpp_transaction_id is not null;
