package top.yealike.pep.control.agents.skill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.yealike.pep.control.agents.domain.SkillComponent;
import top.yealike.pep.control.agents.domain.SkillDraftFile;
import top.yealike.pep.control.agents.domain.SkillRelease;
import top.yealike.pep.control.agents.store.PublicationStore;
import top.yealike.pep.control.agents.store.SkillStore;
import top.yealike.pep.control.support.AiExceptions;
import top.yealike.pep.control.support.JsonSupport;
import top.yealike.pep.runtime.contract.ComponentDigests;
import top.yealike.pep.runtime.contract.Digests;
import top.yealike.pep.runtime.contract.ErrorCodes;
import top.yealike.pep.runtime.contract.SkillBundle;
import top.yealike.pep.runtime.contract.SkillDescriptor;
import top.yealike.pep.runtime.contract.SkillFile;
import top.yealike.pep.runtime.contract.SkillFileInfo;
import top.yealike.pep.runtime.contract.UserIdentity;

/** 文本 Skill 的在线编辑、校验、不可变发布与按版本读取。 */
@Service
public class SkillService {

    private final SkillStore store;
    private final SkillValidator validator;
    private final PublicationStore publications;

    public SkillService(SkillStore store, SkillValidator validator, PublicationStore publications) {
        this.store = store;
        this.validator = validator;
        this.publications = publications;
    }

    public List<SkillComponent> list() {
        return store.list();
    }

    public SkillComponent require(UUID id) {
        return store.find(id).orElseThrow(() -> AiExceptions.notFound("Skill 不存在"));
    }

    public UUID create(UserIdentity actor, String key, String displayName, String description) {
        requireKey(key);
        if (store.findByKey(key).isPresent()) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Skill key 已存在");
        }
        return store.insert(key, displayName, description, actor.username());
    }

    public SkillComponent update(
            UUID id, long expectedRevision, String displayName, String description, boolean enabled) {
        require(id);
        int updated = store.update(id, expectedRevision, displayName, description, enabled);
        if (updated == 0) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Skill 已被其他请求修改，请刷新后重试");
        }
        return require(id);
    }

    @Transactional
    public void delete(UUID id) {
        SkillComponent component = store.lock(id).orElseThrow(() -> AiExceptions.notFound("Skill 不存在"));
        if (store.bindingReferences(component.id()) > 0) {
            throw AiExceptions.conflict(ErrorCodes.COMPONENT_IN_USE, "Skill 已被 Agent 发布引用");
        }
        if (!store.releases(component.id()).isEmpty()) {
            throw AiExceptions.conflict(ErrorCodes.COMPONENT_IN_USE, "Skill 已有发布版本，请停用而不是删除");
        }
        store.deleteDraftFiles(component.id());
        store.delete(component.id());
    }

    public List<Map<String, Object>> fileList(UUID id) {
        require(id);
        List<Map<String, Object>> result = new ArrayList<>();
        for (SkillDraftFile file : store.files(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", file.path());
            row.put("digest", file.digest());
            row.put("size", file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            row.put("entry", "SKILL.md".equals(file.path()));
            row.put("updatedAt", file.updatedAt());
            result.add(row);
        }
        return result;
    }

    public SkillDraftFile file(UUID id, String path) {
        require(id);
        SkillPaths.requireValid(path);
        return store.file(id, path).orElseThrow(() -> AiExceptions.notFound("Skill 文件不存在"));
    }

    @Transactional
    public SkillDraftFile saveFile(UserIdentity actor, UUID id, long expectedRevision, String path, String content) {
        SkillComponent component = require(id);
        SkillPaths.requireValid(path);
        if (store.bumpRevision(id, expectedRevision) == 0) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Skill 已被其他请求修改，请刷新后重试");
        }
        String normalized = SkillPaths.normalizeContent(content);
        String digest = Digests.sha256Hex(normalized);
        store.upsertFile(id, path, normalized, digest);
        return store.file(id, path).orElseThrow(() -> AiExceptions.unavailable("Skill 文件保存失败"));
    }

    @Transactional
    public void deleteFile(UUID id, long expectedRevision, String path) {
        require(id);
        SkillPaths.requireValid(path);
        if (store.bumpRevision(id, expectedRevision) == 0) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Skill 已被其他请求修改，请刷新后重试");
        }
        store.deleteFile(id, path);
    }

    public Map<String, Object> validate(UUID id) {
        SkillComponent component = require(id);
        SkillValidator.Validation validation = validator.validate(component.key(), draftFiles(id));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid", validation.errors().isEmpty());
        result.put("errors", validation.errors());
        result.put("warnings", validation.warnings());
        result.put("metadata", validation.metadata());
        result.put("draftRevision", component.revision());
        result.put("fileCount", store.files(id).size());
        result.put(
                "totalBytes",
                store.files(id).stream()
                        .mapToLong(file -> file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                        .sum());
        return result;
    }

    @Transactional
    public PublishResult publish(
            UserIdentity actor, UUID id, String version, long expectedRevision, String idempotencyKey) {
        SkillComponent component = store.lock(id).orElseThrow(() -> AiExceptions.notFound("Skill 不存在"));
        String publicationKey = idempotencyKey == null || idempotencyKey.isBlank()
                ? UUID.randomUUID().toString()
                : idempotencyKey.strip();
        var existing = publications.findByIdempotency("SKILL", id, publicationKey);
        if (existing.isPresent()) {
            if (version != null
                    && !version.isBlank()
                    && !existing.get().version().equals(requireVersion(version))) {
                throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "幂等键已用于不同的 Skill 发布请求");
            }
            SkillRelease release = store.findRelease(
                            component.key(), existing.get().version())
                    .orElseThrow(() -> AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "发布记录与 Skill 版本不一致"));
            return new PublishResult(release.id(), existing.get().id(), release.version(), release.bundleDigest());
        }
        if (component.revision() != expectedRevision) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "Skill 已被其他请求修改，请刷新后重试");
        }
        String resolvedVersion = version == null || version.isBlank()
                ? nextVersion(component.publishedVersion())
                : requireVersion(version);
        recomputeDraftDigests(id);
        List<SkillFile> files = draftFiles(id);
        SkillValidator.Validation validation = validator.validate(component.key(), files);
        if (!validation.errors().isEmpty()) {
            throw new AiExceptions.AiException(
                    422,
                    "SKILL_INVALID",
                    "Skill 校验未通过：" + validation.errors().getFirst().message());
        }
        if (store.findRelease(component.key(), resolvedVersion).isPresent()) {
            throw AiExceptions.conflict(ErrorCodes.REVISION_CONFLICT, "该 Skill 版本已存在，发布内容不可覆盖");
        }
        long generation = Math.max(component.publicationGeneration() + 1, publications.nextGeneration("SKILL", id));
        SkillBundle bundle = new SkillBundle(
                component.key(),
                resolvedVersion,
                validation.metadata().name(),
                validation.metadata().description(),
                null,
                files);
        String digest = ComponentDigests.skillBundle(bundle);
        bundle = new SkillBundle(
                component.key(),
                resolvedVersion,
                validation.metadata().name(),
                validation.metadata().description(),
                digest,
                files);
        UUID releaseId = store.insertRelease(
                id,
                resolvedVersion,
                generation,
                validation.metadata().name(),
                validation.metadata().description(),
                bundle,
                digest,
                actor.username());
        UUID publicationId = publications.insert(
                "SKILL",
                id,
                resolvedVersion,
                component.revision(),
                generation,
                publicationKey,
                Instant.now().plusSeconds(60));
        publications.insertOutbox(
                "SKILL",
                releaseId,
                generation,
                "PUBLISH_REQUESTED",
                JsonSupport.write(
                        Map.of("releaseId", releaseId, "publicationId", publicationId, "version", resolvedVersion)));
        return new PublishResult(releaseId, publicationId, resolvedVersion, digest);
    }

    public SkillDescriptor descriptor(String key, String version) {
        SkillRelease release = store.findRelease(key, version)
                .filter(item -> "ACTIVE".equals(item.state()))
                .orElseThrow(() -> AiExceptions.conflict(ErrorCodes.RELEASE_NOT_ACTIVE, "Skill 版本未发布"));
        List<SkillFileInfo> files = release.bundle().files().stream()
                .map(file -> new SkillFileInfo(
                        file.path(),
                        file.digest(),
                        file.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        "SKILL.md".equals(file.path())))
                .toList();
        return new SkillDescriptor(key, version, release.name(), release.description(), release.bundleDigest(), files);
    }

    public SkillFile releaseFile(String key, String version, String path) {
        SkillRelease release = store.findRelease(key, version)
                .filter(item -> "ACTIVE".equals(item.state()))
                .orElseThrow(() -> AiExceptions.conflict(ErrorCodes.RELEASE_NOT_ACTIVE, "Skill 版本未发布"));
        SkillPaths.requireValid(path);
        return release.bundle().files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow(() -> AiExceptions.forbidden(ErrorCodes.SKILL_PATH_DENIED, "Skill 路径不在发布包内"));
    }

    public SkillRelease release(UUID releaseId) {
        return store.findRelease(releaseId).orElseThrow(() -> AiExceptions.notFound("Skill 版本不存在"));
    }

    public SkillRelease release(String key, String version) {
        return store.findRelease(key, version).orElseThrow(() -> AiExceptions.notFound("Skill 版本不存在"));
    }

    public UUID requireActiveReleaseId(String key, String version) {
        SkillRelease release = release(key, version);
        if (!"ACTIVE".equals(release.state())) {
            throw AiExceptions.badRequest("Skill 版本未发布，不能绑定：" + key + "@" + version);
        }
        return release.id();
    }

    public List<SkillRelease> releases(UUID skillId) {
        return store.releases(skillId);
    }

    /** 初始化脚本可能先写内容再补摘要；发布前按当前正文重算，避免把占位摘要冻结进 release。 */
    @Transactional
    public int recomputeDraftDigests(UUID id) {
        require(id);
        int changed = 0;
        for (SkillDraftFile file : store.files(id)) {
            String digest = Digests.sha256Hex(file.content());
            if (!digest.equals(file.digest())) {
                store.upsertFile(id, file.path(), file.content(), digest);
                changed++;
            }
        }
        return changed;
    }

    private List<SkillFile> draftFiles(UUID id) {
        return store.files(id).stream()
                .map(file -> new SkillFile(file.path(), file.content(), Digests.sha256Hex(file.content())))
                .sorted(Comparator.comparing(SkillFile::path))
                .toList();
    }

    static void requireKey(String key) {
        if (key == null || !key.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || key.length() < 2 || key.length() > 40) {
            throw AiExceptions.badRequest("Skill key 必须为 2-40 位小写字母/数字，以单连字符分段");
        }
    }

    private static String requireVersion(String version) {
        if (!version.matches("\\d+\\.\\d+\\.\\d+")) {
            throw AiExceptions.badRequest("版本必须为 x.y.z");
        }
        return version;
    }

    private static String nextVersion(String current) {
        if (current == null || !current.matches("\\d+\\.\\d+\\.\\d+")) {
            return "1.0.0";
        }
        String[] parts = current.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    public record PublishResult(UUID releaseId, UUID publicationId, String version, String digest) {}
}
