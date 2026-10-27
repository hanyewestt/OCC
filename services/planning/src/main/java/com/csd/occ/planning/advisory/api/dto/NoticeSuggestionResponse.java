package com.csd.occ.planning.advisory.api.dto;

import com.csd.occ.planning.advisory.domain.AdvisoryDraftPort;
import java.util.List;

public record NoticeSuggestionResponse(String title, String message, List<String> services,
                                       List<String> locations, String generator) {
    public static NoticeSuggestionResponse from(AdvisoryDraftPort.NoticeSuggestion value) {
        return new NoticeSuggestionResponse(value.title(), value.message(), value.services(), value.locations(), value.generator());
    }
}
