package com.csd.occ.planning.diagnosis.domain;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiAuditService {
    private final ModelInvocationRecordRepository records;

    public AiAuditService(ModelInvocationRecordRepository records) { this.records = records; }

    @Transactional(readOnly = true)
    public List<AiAuditRecord> history(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid AI audit page bounds");
        return records.findAllByOrderByCompletedAtDesc(PageRequest.of(page, size)).map(AiAuditRecord::from).getContent();
    }

    @Transactional(readOnly = true)
    public long countHistory() { return records.count(); }
}
