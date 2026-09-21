package sg.edu.smu.occ.ingestion.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sg.edu.smu.occ.ingestion.domain.PollTarget;

@Repository
public interface PollTargetRepository extends JpaRepository<PollTarget, Object> {
}
