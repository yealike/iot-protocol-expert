package top.yealike.ipe.ops.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import top.yealike.ipe.ops.domain.ProtocolDocSection;

public interface ProtocolDocSectionRepository extends JpaRepository<ProtocolDocSection, Long> {
    @Query("select s from ProtocolDocSection s where s.protocol = ?1 and s.version = ?2 "
            + "and lower(s.frameOrAction) = lower(?3)")
    List<ProtocolDocSection> findByFrame(String protocol, String version, String frameOrAction);

    @Query("select s from ProtocolDocSection s where s.protocol = ?1 and s.version = ?2 and "
            + "(lower(s.content) like lower(concat('%', ?3, '%')) or lower(s.keywords) like lower(concat('%', ?3, '%')) "
            + "or lower(s.heading) like lower(concat('%', ?3, '%')))")
    List<ProtocolDocSection> search(String protocol, String version, String keyword);
}
