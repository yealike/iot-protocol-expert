set search_path to pep_agent;

drop table if exists ai_eval_score;
drop table if exists ai_eval_result;
drop table if exists ai_eval_run;
drop table if exists ai_eval_case;
drop table if exists ai_eval_dataset;

alter table ai_conversation drop column if exists source;

set search_path to pep_platform;
