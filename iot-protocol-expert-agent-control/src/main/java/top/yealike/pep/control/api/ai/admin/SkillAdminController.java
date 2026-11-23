package top.yealike.pep.control.api.ai.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.yealike.pep.control.access.CurrentUser;
import top.yealike.pep.control.agents.domain.SkillComponent;
import top.yealike.pep.control.agents.domain.SkillDraftFile;
import top.yealike.pep.control.agents.domain.SkillFileView;
import top.yealike.pep.control.agents.domain.SkillRelease;
import top.yealike.pep.control.agents.domain.SkillValidationResult;
import top.yealike.pep.control.agents.skill.SkillService;
import top.yealike.pep.control.agents.skill.domain.PublishResult;
import top.yealike.pep.control.api.ai.admin.dto.SkillCreate;
import top.yealike.pep.control.api.ai.admin.dto.SkillFileSave;
import top.yealike.pep.control.api.ai.admin.dto.SkillPublish;
import top.yealike.pep.control.api.ai.admin.dto.SkillUpdate;
import top.yealike.pep.runtime.contract.OperationResult;
import top.yealike.pep.runtime.contract.UserIdentity;

/** Skill 草稿文件、校验与不可变发布。 */
@RestController
@RequestMapping("/api/ai/v1")
@PreAuthorize("hasAuthority('ai:admin')")
@Tag(name = "AI Skill 管理", description = "SKILL.md 与参考资料编辑、校验和发布")
public class SkillAdminController {

    private final SkillService skills;

    public SkillAdminController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping("/skills")
    @Operation(summary = "查询 Skill 列表")
    public List<SkillComponent> listSkills() {
        return skills.list();
    }

    @PostMapping("/skills")
    @Operation(summary = "创建 Skill 草稿")
    public SkillComponent createSkill(@RequestBody SkillCreate request) {
        UserIdentity actor = CurrentUser.require();
        UUID id = skills.create(actor, request.key(), request.displayName(), request.description());
        return skills.require(id);
    }

    @PutMapping("/skills/{id}")
    public SkillComponent updateSkill(@PathVariable UUID id, @RequestBody SkillUpdate request) {
        return skills.update(
                id, request.expectedRevision(), request.displayName(), request.description(), request.enabled());
    }

    @DeleteMapping("/skills/{id}")
    public OperationResult deleteSkill(@PathVariable UUID id) {
        skills.delete(id);
        return new OperationResult(true, "DELETED");
    }

    @GetMapping("/skills/{id}/files")
    public List<SkillFileView> skillFiles(@PathVariable UUID id) {
        return skills.fileList(id);
    }

    @GetMapping("/skills/{id}/file")
    @Operation(summary = "读取 Skill 草稿文件")
    public SkillDraftFile skillFile(
            @Parameter(description = "Skill 组件 ID") @PathVariable UUID id,
            @Parameter(description = "Skill 包内相对路径，例如 SKILL.md", example = "SKILL.md") @RequestParam String path) {
        return skills.file(id, path);
    }

    @PutMapping("/skills/{id}/file")
    @Operation(summary = "保存 Skill 草稿文件")
    public SkillDraftFile saveSkillFile(
            @PathVariable UUID id, @RequestParam String path, @RequestBody SkillFileSave request) {
        UserIdentity actor = CurrentUser.require();
        return skills.saveFile(actor, id, request.expectedRevision(), path, request.content());
    }

    @DeleteMapping("/skills/{id}/file")
    public OperationResult deleteSkillFile(
            @PathVariable UUID id, @RequestParam String path, @RequestParam long expectedRevision) {
        skills.deleteFile(id, expectedRevision, path);
        return new OperationResult(true, "DELETED");
    }

    @PostMapping("/skills/{id}/validate")
    @Operation(summary = "校验 Skill 草稿")
    public SkillValidationResult validateSkill(@Parameter(description = "Skill 组件 ID") @PathVariable UUID id) {
        return skills.validate(id);
    }

    @GetMapping("/skills/{id}/releases")
    public List<SkillRelease> skillReleases(@PathVariable UUID id) {
        return skills.releases(id);
    }

    @PostMapping("/skills/{id}/publish")
    @Operation(summary = "发布 Skill 版本", description = "冻结 Skill 文件包并异步注册组件版本")
    public ResponseEntity<PublishResult> publishSkill(@PathVariable UUID id, @RequestBody SkillPublish request) {
        UserIdentity actor = CurrentUser.require();
        PublishResult result =
                skills.publish(actor, id, request.version(), request.expectedRevision(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }
}
