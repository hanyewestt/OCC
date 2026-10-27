package com.csd.occ.planning.advisory.domain;

import com.csd.occ.planning.diagnosis.domain.PlanningScopePort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdvisoryGroupService {
    private final AdvisoryProposalRepository proposals;
    private final InstructionService instructions;
    private final PlanningScopePort scopes;

    public AdvisoryGroupService(AdvisoryProposalRepository proposals, InstructionService instructions, PlanningScopePort scopes) {
        this.proposals = proposals; this.instructions = instructions; this.scopes = scopes;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AdvisoryGroup> pending(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid page or size");
        var request = PageRequest.of(page, size);
        var runId = scopes.current().demoRunId();
        if (runId == null) return Page.empty(request);
        var anchors = proposals.findPendingGroupIds(runId, request);
        if (anchors.isEmpty()) return new PageImpl<>(List.of(), request, anchors.getTotalElements());
        var members = proposals.findPendingGroupMembers(anchors.getContent());
        var byId = members.stream().collect(Collectors.toMap(AdvisoryProposal::id, Function.identity()));
        Map<AdvisoryGroup.Key, List<AdvisoryProposal>> groups = new LinkedHashMap<>();
        for (var proposal : members) groups.computeIfAbsent(AdvisoryGroup.Key.from(proposal), key -> new ArrayList<>()).add(proposal);
        var found = anchors.stream().map(id -> {
            var key = AdvisoryGroup.Key.from(byId.get(id));
            return new AdvisoryGroup(key, List.copyOf(groups.get(key)));
        }).toList();
        return new PageImpl<>(found, request, anchors.getTotalElements());
    }

    @Transactional
    public List<IssuedInstruction> decide(List<UUID> proposalIds, String actor) {
        if (proposalIds == null || proposalIds.isEmpty()
                || proposalIds.stream().anyMatch(id -> id == null) || new HashSet<>(proposalIds).size() != proposalIds.size())
            throw new IllegalArgumentException("Select all distinct proposals from the reviewed advisory group");
        var selected = proposalIds.stream().sorted().map(id -> proposals.findForUpdate(id)
                .orElseThrow(AdvisoryNotFoundException::new)).toList();
        var key = AdvisoryGroup.Key.from(selected.getFirst());
        if (!key.demoRunId().equals(scopes.current().demoRunId())
                || selected.stream().anyMatch(value -> !key.equals(AdvisoryGroup.Key.from(value))))
            throw new AdvisoryConflictException("Advisory group changed; refresh and review the service and direction");
        boolean pending = selected.stream().allMatch(value -> "PENDING".equals(value.status()));
        boolean approved = selected.stream().allMatch(value -> "APPROVED".equals(value.status()));
        if (!pending && !approved) throw new AdvisoryConflictException("Advisory group changed; refresh and review its recipients");
        if (pending) {
            var current = proposals.findPendingGroupMembers(List.of(selected.getFirst().id())).stream()
                    .map(AdvisoryProposal::id).collect(Collectors.toSet());
            if (!current.equals(new HashSet<>(proposalIds)))
                throw new AdvisoryConflictException("Advisory group recipients changed; refresh and review before sending");
        }
        // One outer transaction keeps individual decisions, evidence and deliveries atomic.
        try {
            return selected.stream().map(value -> instructions.decide(value.id(), null, null, actor, null)).toList();
        } catch (IllegalArgumentException invalid) {
            throw new AdvisoryConflictException("A recipient is no longer eligible: " + invalid.getMessage());
        }
    }
}
