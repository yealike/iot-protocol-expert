delete from pep_platform.role_permission
where permission_code in ('investigation:create', 'investigation:read', 'investigation:operate');
