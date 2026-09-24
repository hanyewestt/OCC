package sg.edu.smu.occ.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.csd.occ.ingestion.infrastructure.config.ArrivalPollingProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import sg.edu.smu.occ.ingestion.domain.PollTarget;
import sg.edu.smu.occ.ingestion.repository.PollTargetRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "occ.ingestion.seed-enabled", havingValue = "true", matchIfMissing = true)
public class InitSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(InitSeeder.class);

    private final PollTargetRepository pollTargetRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private final ArrivalPollingProperties properties;
    private final Clock clock;

    public InitSeeder(PollTargetRepository pollTargetRepository, RestTemplateBuilder restTemplateBuilder,
                      ArrivalPollingProperties properties, Clock clock) {
        this.pollTargetRepository = pollTargetRepository;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = new ObjectMapper();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (pollTargetRepository.count() > 0) {
            log.info("Poll targets already seeded.");
            return;
        }

        log.info("Seeding poll targets for routes 190 and 972...");
        Set<String> targetStops = new HashSet<>();
        
        // BusRoutes returns every route: collect both profiles in one paginated traversal.
        fetchStopsForRoutes(targetStops, Set.of("190", "972"));

        log.info("Found {} unique stops for routes 190 and 972. Saving targets...", targetStops.size());
        
        for (String stopCode : targetStops) {
            PollTarget target = new PollTarget();
            target.setSource("DATAMALL");
            target.setScopeKey("stop:" + stopCode);
            target.setStopCode(stopCode);
            target.setEnabled(true);
            target.setPollIntervalSeconds(60);
            // Legacy JPA entity uses OffsetDateTime; preserve its mapping during this focused fix.
            target.setCreatedAt(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            target.setUpdatedAt(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            pollTargetRepository.save(target);
        }
        
        log.info("Seeding complete.");
    }

    private void fetchStopsForRoutes(Set<String> targetStops, Set<String> routes) {
        int skip = 0;
        boolean hasMore = true;
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("AccountKey", properties.accountKey());
        HttpEntity<String> entity = new HttpEntity<>(headers);

        while (hasMore) {
            String url = properties.baseUrl().toString().replaceAll("/$", "") + "/BusRoutes?$skip=" + skip;
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    JsonNode valueNode = root.path("value");
                    if (!valueNode.isArray()) {
                        throw new IllegalStateException("BusRoutes response at skip " + skip
                                + " does not contain an array-valued 'value' field");
                    }
                    if (valueNode.isEmpty()) {
                        hasMore = false;
                    } else {
                        for (JsonNode routeNode : valueNode) {
                            if (routes.contains(routeNode.path("ServiceNo").asText())) {
                                targetStops.add(routeNode.path("BusStopCode").asText());
                            }
                        }
                        skip += 500;
                    }
                } else {
                    throw new IllegalStateException("BusRoutes request at skip " + skip
                            + " returned HTTP " + response.getStatusCode().value());
                }
            } catch (Exception e) {
                log.error("Failed to fetch routes at skip {}", skip, e);
                throw new IllegalStateException("Could not complete BusRoutes seed at skip " + skip, e);
            }
        }
    }
}
