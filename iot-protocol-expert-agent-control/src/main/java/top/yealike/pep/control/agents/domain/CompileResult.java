package top.yealike.pep.control.agents.domain;

import java.util.List;
import top.yealike.pep.runtime.contract.AgentManifest;

/** 控制面领域记录。 */
public record CompileResult(
        boolean valid,
        AgentManifest manifest,
        String manifestDigest,
        List<CompileIssue> errors,
        List<CompileIssue> warnings) {}
