package com.csd.occ.bunching.domain;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
public interface ConsumerStateRepository extends JpaRepository<ConsumerState, Integer> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) Optional<ConsumerState> findWithLockById(int id);
}
