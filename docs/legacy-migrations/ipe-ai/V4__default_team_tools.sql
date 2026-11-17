-- Correct the original unmodified OPS seed: the leader needs delegation tools.
-- Do not broaden a policy that an administrator has customized.
update ai_permission_policy
set tool_names = tool_names || '["ask_protocol_agent", "ask_order_agent"]'::jsonb,
    updated_at = now()
where role = 'OPS' and policy_version = 'v1'
  and tool_names = '["get_order_detail", "get_order_bills", "get_device_detail", "get_order_telemetry_summary", "search_protocol_knowledge"]'::jsonb;
