-- =============================================================================
-- 充电桩产品关联已发布的协议文档
--
-- resolve_device 必须返回 protocol_document_id，Agent 才能继续调用
-- search_protocol_knowledge 检索对应文档。文档本身不做任何内容变更。
-- =============================================================================

set search_path to pep_domain;

update product p
set protocol_document_id = docs.id,
    updated_at = now()
from lateral (
    select d.id
    from protocol_document d
    where d.status = 'ACTIVE'
      and (lower(d.protocol_code) like '%ykc%' or d.protocol_code like '%云快充%')
    order by d.id desc
    limit 1
) docs
where p.protocol = 'YKC16'
  and p.protocol_document_id is null;

update product p
set protocol_document_id = docs.id,
    updated_at = now()
from lateral (
    select d.id
    from protocol_document d
    where d.status = 'ACTIVE'
      and (lower(d.protocol_code) like '%ocpp%' or lower(d.protocol_name_en) like '%ocpp%')
    order by d.id desc
    limit 1
) docs
where p.protocol = 'OCPP16J'
  and p.protocol_document_id is null;
