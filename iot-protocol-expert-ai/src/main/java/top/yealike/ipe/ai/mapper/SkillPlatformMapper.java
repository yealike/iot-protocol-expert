package top.yealike.ipe.ai.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillComponent;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillDraftFile;
import top.yealike.ipe.ai.platform.domain.PlatformRecords.SkillRelease;

public interface SkillPlatformMapper {

    List<SkillComponent> listComponents();

    List<SkillComponent> findComponent(@Param("id") UUID id);

    List<SkillComponent> findComponentByKey(@Param("key") String key);

    List<SkillComponent> lockComponent(@Param("id") UUID id);

    int insertComponent(
            @Param("id") UUID id,
            @Param("key") String key,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("actor") String actor);

    int updateComponent(
            @Param("id") UUID id,
            @Param("expectedRevision") long expectedRevision,
            @Param("displayName") String displayName,
            @Param("description") String description,
            @Param("enabled") boolean enabled);

    int setComponentEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);

    int bumpRevision(@Param("id") UUID id, @Param("expectedRevision") long expectedRevision);

    int deleteComponent(@Param("id") UUID id);

    int deleteDraftFiles(@Param("id") UUID id);

    long bindingReferences(@Param("skillId") UUID skillId);

    List<SkillDraftFile> files(@Param("skillId") UUID skillId);

    List<SkillDraftFile> file(@Param("skillId") UUID skillId, @Param("path") String path);

    int upsertFile(
            @Param("id") UUID id,
            @Param("skillId") UUID skillId,
            @Param("path") String path,
            @Param("content") String content,
            @Param("digest") String digest);

    int bumpDraftRevision(@Param("skillId") UUID skillId);

    int deleteFile(@Param("skillId") UUID skillId, @Param("path") String path);

    int insertRelease(
            @Param("id") UUID id,
            @Param("skillId") UUID skillId,
            @Param("version") String version,
            @Param("generation") long generation,
            @Param("name") String name,
            @Param("description") String description,
            @Param("bundleJson") String bundleJson,
            @Param("digest") String digest,
            @Param("actor") String actor);

    List<SkillRelease> findRelease(@Param("id") UUID id);

    List<SkillRelease> findReleaseByKey(@Param("key") String key, @Param("version") String version);

    List<SkillRelease> releases(@Param("skillId") UUID skillId);

    int setReleaseState(@Param("releaseId") UUID releaseId, @Param("state") String state);

    int publishComponent(
            @Param("skillId") UUID skillId,
            @Param("version") String version,
            @Param("expectedGeneration") long expectedGeneration);

    int countPublished(
            @Param("skillId") UUID skillId,
            @Param("version") String version,
            @Param("expectedGeneration") long expectedGeneration);
}
