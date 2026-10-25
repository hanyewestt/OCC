package com.csd.occ.planning.recommendation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusInstructionPort {
    boolean saveIfAbsent(BusInstruction instruction);

    Optional<BusInstruction> findById(UUID instructionId);

    boolean acknowledgeIfSent(BusInstruction instruction);

    List<BusInstruction> findByBusReference(String busReference, int offset, int limit);

    long countByBusReference(String busReference);
}
