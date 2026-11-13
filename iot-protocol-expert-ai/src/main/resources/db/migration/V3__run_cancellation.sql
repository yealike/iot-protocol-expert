-- A cancellation request retains the conversation lease until the worker stops.
drop index if exists uq_ai_run_conversation_running;
create unique index uq_ai_run_conversation_running on ai_run (conversation_id)
    where status in ('RUNNING', 'PAUSE_REQUESTED', 'CANCEL_REQUESTED');
