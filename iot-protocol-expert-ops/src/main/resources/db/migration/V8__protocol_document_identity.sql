ALTER TABLE ipe_ops.protocol_document ADD COLUMN protocol_name_en varchar(64);
UPDATE ipe_ops.protocol_document SET protocol_name_en = CASE WHEN protocol_code = '云快充' THEN 'YKC' WHEN upper(protocol_code) = 'OCPP' THEN 'OCPP' ELSE protocol_code END;
ALTER TABLE ipe_ops.protocol_document ALTER COLUMN protocol_name_en SET NOT NULL;
ALTER TABLE ipe_ops.product ADD COLUMN protocol_document_id bigint REFERENCES ipe_ops.protocol_document(id);
UPDATE ipe_ops.product p SET protocol_document_id = (SELECT d.id FROM ipe_ops.protocol_document d WHERE d.status = 'ACTIVE' AND ((p.protocol = 'YKC16' AND upper(d.protocol_name_en) = 'YKC' AND lower(d.document_version) = '1.6') OR (p.protocol = 'OCPP16J' AND upper(d.protocol_name_en) = 'OCPP' AND lower(d.document_version) = '1.6j')) ORDER BY d.id DESC LIMIT 1) WHERE p.protocol IN ('YKC16', 'OCPP16J');
CREATE INDEX idx_product_protocol_document ON ipe_ops.product(protocol_document_id);
