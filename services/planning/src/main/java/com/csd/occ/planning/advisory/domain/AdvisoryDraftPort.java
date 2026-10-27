package com.csd.occ.planning.advisory.domain;

public interface AdvisoryDraftPort {
    Result propose(AdvisoryContextPort.Hazard hazard, AdvisoryContextPort.Bus bus);
    default NoticeSuggestion suggestNotice(AdvisoryContextPort.Hazard hazard) {
        var facts = hazard.facts();
        var title = facts.title() == null || facts.title().isBlank() ? "Service disruption update" : facts.title().strip();
        var message = java.util.stream.Stream.of(facts.description(), facts.instruction())
                .filter(value -> value != null && !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining("\n"));
        var services = hazard.exposures().stream().map(AdvisoryContextPort.Exposure::serviceId).distinct().toList();
        var locations = hazard.exposures().stream().map(AdvisoryContextPort.Exposure::stopName).distinct().toList();
        if (locations.isEmpty()) locations = facts.locations().stream().map(HazardEventFacts.Location::description)
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        return new NoticeSuggestion(title, message, services, locations, "SOURCE_FACTS");
    }
    record Result(AdvisoryCandidate candidate, String model, String input, String output) {}
    record NoticeSuggestion(String title, String message, java.util.List<String> services,
                            java.util.List<String> locations, String generator) {
        public NoticeSuggestion {
            services = java.util.List.copyOf(services);
            locations = java.util.List.copyOf(locations);
        }
    }
}
