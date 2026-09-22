package sg.edu.smu.occ.ingestion.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import sg.edu.smu.occ.ingestion.domain.PollRun;
import java.util.UUID;

@Repository
public interface PollRunRepository extends JpaRepository<PollRun, UUID> {
}
