package top.yealike.pep.control.investigations.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import top.yealike.pep.control.investigations.InvestigationRecords.Approval;
import top.yealike.pep.control.investigations.InvestigationRecords.Conclusion;
import top.yealike.pep.control.investigations.InvestigationRecords.Evidence;
import top.yealike.pep.control.investigations.InvestigationRecords.Experiment;
import top.yealike.pep.control.investigations.InvestigationRecords.Hypothesis;
import top.yealike.pep.control.investigations.InvestigationRecords.Task;
import top.yealike.pep.control.investigations.InvestigationRecords.TaskEvent;

public interface InvestigationMapper {

    int insertTask(Task task);

    Task findTask(@Param("id") UUID id);

    Task lockTask(@Param("id") UUID id);

    List<Task> listTasks(
            @Param("projectId") UUID projectId,
            @Param("ownerId") String ownerId,
            @Param("allRead") boolean allRead,
            @Param("status") String status,
            @Param("limit") int limit);

    int updateTaskStatus(
            @Param("id") UUID id, @Param("expectedVersion") long expectedVersion, @Param("status") String status);

    long maxEventSeq(@Param("taskId") UUID taskId);

    int insertEvent(TaskEvent event);

    List<TaskEvent> listEvents(
            @Param("taskId") UUID taskId, @Param("afterSeq") long afterSeq, @Param("limit") int limit);

    Evidence findEvidenceByHash(
            @Param("projectId") UUID projectId,
            @Param("sourceType") String sourceType,
            @Param("sourceId") String sourceId,
            @Param("contentHash") String contentHash);

    int insertEvidence(Evidence evidence);

    int linkEvidence(
            @Param("taskId") UUID taskId, @Param("evidenceId") UUID evidenceId, @Param("addedBy") String addedBy);

    List<Evidence> listEvidence(@Param("taskId") UUID taskId);

    int countLinkedEvidence(@Param("taskId") UUID taskId, @Param("evidenceIds") List<UUID> evidenceIds);

    int insertHypothesis(Hypothesis hypothesis);

    List<Hypothesis> listHypotheses(@Param("taskId") UUID taskId);

    int updateHypothesisStatus(@Param("taskId") UUID taskId, @Param("id") UUID id, @Param("status") String status);

    int insertExperiment(Experiment experiment);

    List<Experiment> listExperiments(@Param("taskId") UUID taskId);

    int updateExperiment(
            @Param("taskId") UUID taskId,
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("simRunId") String simRunId,
            @Param("resultSummary") String resultSummary,
            @Param("resultRef") String resultRef);

    long nextConclusionVersion(@Param("taskId") UUID taskId);

    int insertConclusion(Conclusion conclusion);

    List<Conclusion> listConclusions(@Param("taskId") UUID taskId);

    int insertApproval(Approval approval);

    Approval findApproval(@Param("taskId") UUID taskId, @Param("id") UUID id);

    int useApproval(@Param("taskId") UUID taskId, @Param("id") UUID id);

    List<Approval> listApprovals(@Param("taskId") UUID taskId);
}
