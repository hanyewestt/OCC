package com.csd.occ.planning.advisory.domain;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AssignmentBusGuards {
    private static final Comparator<Key> ORDER = Comparator.comparing((Key key) -> key.runId().toString())
            .thenComparing(Key::busId);
    private final AssignmentBusGuardRepository guards;
    public AssignmentBusGuards(AssignmentBusGuardRepository guards) { this.guards = guards; }

    @Transactional(propagation = Propagation.MANDATORY)
    public AssignmentBusGuard lock(UUID runId, String busId) {
        guards.insertIfAbsent(runId, busId);
        return guards.findForUpdate(runId, busId).orElseThrow();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<AssignmentBusGuard> lockAll(Collection<Key> keys) {
        return keys.stream().distinct().sorted(ORDER).map(key -> lock(key.runId(), key.busId())).toList();
    }

    public record Key(UUID runId, String busId) { }
}
