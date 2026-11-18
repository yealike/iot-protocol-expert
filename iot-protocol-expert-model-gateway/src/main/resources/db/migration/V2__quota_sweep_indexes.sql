create index if not exists ix_quota_reservation_held_deadline
    on pep_metering.quota_reservation (dispatch_deadline)
    where state = 'HELD';

create index if not exists ix_quota_reservation_dispatched_created
    on pep_metering.quota_reservation (created_at)
    where state = 'DISPATCHED';
