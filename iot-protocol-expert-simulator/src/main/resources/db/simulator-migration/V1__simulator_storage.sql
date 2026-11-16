CREATE TABLE ipe_simulator.device_state (
    state_key varchar(512) PRIMARY KEY,
    device_id bigint,
    protocol varchar(32) NOT NULL,
    pile_code varchar(255) NOT NULL,
    snapshot text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT current_timestamp
);

CREATE TABLE ipe_simulator.charging_record (
    state_key varchar(512) NOT NULL REFERENCES ipe_simulator.device_state(state_key),
    transaction_no varchar(512) NOT NULL,
    device_id bigint,
    protocol varchar(32) NOT NULL,
    pile_code varchar(255) NOT NULL,
    gun_no varchar(2) NOT NULL,
    start_time timestamp NOT NULL,
    end_time timestamp NOT NULL,
    meter_start numeric(24, 4) NOT NULL,
    meter_end numeric(24, 4) NOT NULL,
    total_energy numeric(24, 4) NOT NULL,
    loss_total_energy numeric(24, 4) NOT NULL,
    total_amount numeric(24, 4) NOT NULL,
    stop_reason integer NOT NULL,
    bill jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (state_key, transaction_no)
);

CREATE INDEX charging_record_device_time ON ipe_simulator.charging_record (device_id, end_time DESC);

COMMENT ON TABLE ipe_simulator.device_state IS '模拟器独立状态：电表、费率、充电检查点、待补传账单与最近历史';
COMMENT ON TABLE ipe_simulator.charging_record IS '模拟器充电账单永久归档，独立于运营平台订单';
COMMENT ON COLUMN ipe_simulator.charging_record.bill IS '完整账单，包含尖峰平谷电量、费率、金额与车辆信息';
