-- 一期不支持暂停/恢复；遗留暂停状态收敛为失败，避免会话被永久阻塞。
update ai_run
set status = 'FAILED',
    error_code = 'RUN_PAUSE_RETIRED',
    error_message = '优先级暂停能力已下线，遗留暂停任务已终止',
    finished_at = coalesce(finished_at, now()),
    updated_at = now(),
    lease_owner = null,
    lease_until = null
where status in ('PAUSE_REQUESTED', 'PAUSED');

drop index if exists uq_ai_run_conversation_running;
create unique index uq_ai_run_conversation_running
    on ai_run (conversation_id)
    where status in ('DISPATCHING', 'RUNNING', 'CANCEL_REQUESTED');
