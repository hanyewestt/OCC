package com.csd.occ.planning.recommendation.infrastructure.fixture;

import com.csd.occ.planning.recommendation.domain.BunchingMitigationContextPort;
import com.csd.occ.planning.recommendation.domain.BunchingMitigationInputs;
import com.csd.occ.planning.recommendation.domain.ConfirmedBunchingEvent;
import com.csd.occ.planning.recommendation.infrastructure.config.RecommendationFixtureProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ClasspathBunchingMitigationContextAdapter implements BunchingMitigationContextPort {

    private final Map<BunchingMitigationFixtureCatalog.FixtureKey, BunchingMitigationInputs> fixtures;

    public ClasspathBunchingMitigationContextAdapter(
            ObjectMapper objectMapper,
            RecommendationFixtureProperties properties) {
        this.fixtures = loadFixtures(objectMapper, properties);
    }

    @Override
    public Optional<BunchingMitigationInputs> findFor(ConfirmedBunchingEvent event) {
        BunchingMitigationFixtureCatalog.FixtureKey key =
                new BunchingMitigationFixtureCatalog.FixtureKey(
                        event.serviceId(), event.directionId(), event.monitoredStopCode());
        return Optional.ofNullable(fixtures.get(key));
    }

    private Map<BunchingMitigationFixtureCatalog.FixtureKey, BunchingMitigationInputs> loadFixtures(
            ObjectMapper objectMapper,
            RecommendationFixtureProperties properties) {
        try (InputStream input = properties.location().getInputStream()) {
            BunchingMitigationFixtureCatalog catalog =
                    objectMapper.readValue(input, BunchingMitigationFixtureCatalog.class);
            return catalog.entries().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            BunchingMitigationFixtureCatalog.Entry::key,
                            BunchingMitigationFixtureCatalog.Entry::inputs));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to load recommendation fixtures from " + properties.location(), exception);
        }
    }
}
