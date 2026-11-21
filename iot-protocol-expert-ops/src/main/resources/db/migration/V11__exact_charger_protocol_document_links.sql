-- 充电桩产品只能绑定协议名称和版本都精确匹配的唯一 ACTIVE 文档。
-- 某协议尚未上传文档时保持未绑定，允许平台启动后再上传；
-- 同版本存在多份候选时明确失败，避免按 id 顺序任意选一份造成错绑。

set search_path to pep_domain;

do $$
declare
    ykc_document_count bigint;
    ocpp_document_count bigint;
    ykc_document_id bigint;
    ocpp_document_id bigint;
begin
    select count(*), min(id)
      into ykc_document_count, ykc_document_id
      from protocol_document
     where status = 'ACTIVE'
       and upper(protocol_name_en) = 'YKC'
       and lower(document_version) = '1.6';

    if ykc_document_count > 1 then
        raise exception 'V11: expected one ACTIVE YKC 1.6 protocol document, found %',
            ykc_document_count;
    end if;

    select count(*), min(id)
      into ocpp_document_count, ocpp_document_id
      from protocol_document
     where status = 'ACTIVE'
       and upper(protocol_name_en) = 'OCPP'
       and lower(document_version) = '1.6j';

    if ocpp_document_count > 1 then
        raise exception 'V11: expected one ACTIVE OCPP 1.6j protocol document, found %',
            ocpp_document_count;
    end if;

    update product p
       set protocol_document_id = ykc_document_id,
           updated_at = now()
     where p.protocol = 'YKC16'
       and ykc_document_count = 1
       and p.protocol_document_id is distinct from ykc_document_id;

    update product p
       set protocol_document_id = ocpp_document_id,
           updated_at = now()
     where p.protocol = 'OCPP16J'
       and ocpp_document_count = 1
       and p.protocol_document_id is distinct from ocpp_document_id;
end
$$;
