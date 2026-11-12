-- 协议名称与版本分开保存；运行时协议编码仍保留在产品和设备中。
UPDATE ipe_ops.protocol_document SET protocol_code = '云快充' WHERE protocol_code = 'YKC16';
UPDATE ipe_ops.protocol_document SET protocol_code = 'OCPP', document_version = CASE WHEN lower(document_version) = '1.6' THEN '1.6j' ELSE document_version END WHERE protocol_code = 'OCPP16J';
