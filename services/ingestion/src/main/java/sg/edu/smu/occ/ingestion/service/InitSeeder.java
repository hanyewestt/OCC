package sg.edu.smu.occ.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import sg.edu.smu.occ.ingestion.domain.PollTarget;
import sg.edu.smu.occ.ingestion.repository.PollTargetRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

@Component
public class InitSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(InitSeeder.class);

    private final PollTargetRepository pollTargetRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${datamall.base-url}")
    private String baseUrl;

    @Value("${datamall.account-key}")
    private String accountKey;

    public InitSeeder(PollTargetRepository pollTargetRepository, RestTemplateBuilder restTemplateBuilder) {
        this.pollTargetRepository = pollTargetRepository;
        this.restTemplate = restTemplateBuilder.build();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void run(String... args) throws Exception {
        if (pollTargetRepository.count() > 0) {
            log.info("Poll targets already seeded.");
            return;
        }

        log.info("Seeding poll targets for routes 190 and 972...");
        Set<String> targetStops = new HashSet<>();
        
        // Fetch stops for 190 and 972
        fetchStopsForRoute(targetStops, "190");
        fetchStopsForRoute(targetStops, "972");

        log.info("Found {} unique stops for routes 190 and 972. Saving targets...", targetStops.size());
        
        for (String stopCode : targetStops) {
            PollTarget target = new PollTarget();
            target.setSource("DATAMALL");
            target.setScopeKey("stop:" + stopCode);
            target.setStopCode(stopCode);
            target.setEnabled(true);
            target.setPollIntervalSeconds(60);
            target.setCreatedAt(OffsetDateTime.now());
            target.setUpdatedAt(OffsetDateTime.now());
            pollTargetRepository.save(target);
        }
        
        log.info("Seeding complete.");
    }

    private void fetchStopsForRoute(Set<String> targetStops, String routeNo) {
        int skip = 0;
        boolean hasMore = true;
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("AccountKey", accountKey);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        while (hasMore) {
            String url = baseUrl + "/BusRoutes?$skip=" + skip;
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    JsonNode root = objectMapper.readTree(response.getBody());
                    JsonNode valueNode = root.path("value");
                    
                    if (valueNode.isEmpty()) {
                        hasMore = false;
                    } else {
                        for (JsonNode routeNode : valueNode) {
                            if (routeNo.equals(routeNode.path("ServiceNo").asText())) {
                                targetStops.add(routeNode.path("BusStopCode").asText());
                            }
                        }
                        skip += 500;
                    }
                } else {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to fetch routes at skip {}", skip, e);
                hasMore = false;
            }
        }
    }
}
