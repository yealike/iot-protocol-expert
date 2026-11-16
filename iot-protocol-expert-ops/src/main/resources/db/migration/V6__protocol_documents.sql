CREATE TABLE ipe_ops.protocol_document (
    id bigserial PRIMARY KEY,
    protocol_code varchar(32) NOT NULL,
    document_version varchar(64) NOT NULL,
    title varchar(256) NOT NULL,
    original_filename varchar(255) NOT NULL,
    storage_key varchar(64) NOT NULL UNIQUE,
    content_type varchar(128) NOT NULL,
    file_size bigint NOT NULL,
    sha256 char(64) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    uploaded_by varchar(64) NOT NULL,
    created_at timestamp NOT NULL DEFAULT now(),
    updated_at timestamp NOT NULL DEFAULT now()
);
CREATE INDEX idx_protocol_document_lookup ON ipe_ops.protocol_document (protocol_code, document_version, status, id);
