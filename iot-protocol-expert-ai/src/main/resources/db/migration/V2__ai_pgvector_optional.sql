-- pgvector 为可选能力：扩展不可用时自动降级为关键字检索，不阻塞启动与迁移。
-- 维度 1024 对应默认 embedding 配置；更换模型时必须新建索引版本并重建，不得混入同一索引。
do $$
begin
    begin
        create extension if not exists vector;
    exception when others then
        raise notice 'pgvector extension unavailable, keyword retrieval only: %', sqlerrm;
    end;

    if exists (select 1 from pg_extension where extname = 'vector') then
        execute 'alter table ai_chunk add column if not exists embedding vector(1024)';
        begin
            execute 'create index if not exists ix_ai_chunk_embedding on ai_chunk using hnsw (embedding vector_cosine_ops)';
        exception when others then
            raise notice 'pgvector hnsw index skipped, falling back to exact scan: %', sqlerrm;
        end;
    end if;
end $$;