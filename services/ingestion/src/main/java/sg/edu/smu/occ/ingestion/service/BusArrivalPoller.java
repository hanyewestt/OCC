package sg.edu.smu.occ.ingestion.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import sg.edu.smu.occ.ingestion.domain.PollRun;
import sg.edu.smu.occ.ingestion.domain.PollTarget;
import sg.edu.smu.occ.ingestion.domain.RawObservation;
import sg.edu.smu.occ.ingestion.repository.PollRunRepository;
import sg.edu.smu.occ.ingestion.repository.PollTargetRepository;
import sg.edu.smu.occ.ingestion.repository.RawObservationRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BusArrivalPoller {
    private static final Logger log = LoggerFactory.getLogger(BusArrivalPoller.class);

    private final PollTargetRepository pollTargetRepository;
    private final PollRunRepository pollRunRepository;
    private final RawObservationRepository rawObservationRepository;
    private final RestTemplate restTemplate;

    @Value("${datamall.base-url}")
    private String baseUrl;

    @Value("${datamall.account-key}")
    private String accountKey;

    public BusArrivalPoller(PollTargetRepository pollTargetRepository,
                            PollRunRepository pollRunRepository,
                            RawObservationRepository rawObservationRepository,
                            RestTemplateBuilder restTemplateBuilder) {
        this.pollTargetRepository = pollTargetRepository;
        this.pollRunRepository = pollRunRepository;
        this.rawObservationRepository = rawObservationRepository;
        this.restTemplate = restTemplateBuilder.build();
    }

    @Scheduled(fixedRate = 60000)
    public void pollBusArrivals() {
        log.info("Starting Bus Arrival polling loop...");
        List<PollTarget> targets = pollTargetRepository.findAll();
        
        for (PollTarget target : targets) {
            if (!Boolean.TRUE.equals(target.getEnabled())) continue;
            
            OffsetDateTime now = OffsetDateTime.now();
            String idempotencyKey = target.getSource() + "-" + target.getScopeKey() + "-" + now.toEpochSecond() / 60;
            
            PollRun run = new PollRun();
            run.setId(UUID.randomUUID());
            run.setSource(target.getSource());
            run.setScopeKey(target.getScopeKey());
            run.setIdempotencyKey(idempotencyKey);
            run.setStartedAt(now);
            run.setOutcome("RUNNING");
            run.setRecordsReceived(0);
            pollRunRepository.save(run);

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.set("AccountKey", accountKey);
                HttpEntity<String> entity = new HttpEntity<>(headers);
                
                String url = baseUrl + "/v3/BusArrival?BusStopCode=" + target.getStopCode();
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
                
                run.setHttpStatus(response.getStatusCode().value());
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    RawObservation obs = new RawObservation();
                    obs.setId(UUID.randomUUID());
                    obs.setPollRunId(run.getId());
                    obs.setSource(target.getSource());
                    obs.setSourceKey(target.getScopeKey());
                    obs.setRequestParameters("{\"BusStopCode\":\"" + target.getStopCode() + "\"}");
                    obs.setPayload(response.getBody());
                    obs.setPayloadChecksum(sha256(response.getBody()));
                    obs.setFetchedAt(OffsetDateTime.now());
                    obs.setIngestedAt(OffsetDateTime.now());
                    obs.setHttpStatus(response.getStatusCode().value());
                    obs.setClassification("OBSERVATION_ONLY");
                    
                    rawObservationRepository.save(obs);
                    
                    run.setOutcome("SUCCESS");
                    run.setRecordsReceived(1);
                } else {
                    run.setOutcome("FAILED");
                    run.setErrorCode(String.valueOf(response.getStatusCode().value()));
                }
            } catch (Exception e) {
                log.error("Failed to poll target {}", target.getScopeKey(), e);
                run.setOutcome("FAILED");
                run.setErrorMessage(e.getMessage());
            } finally {
                run.setCompletedAt(OffsetDateTime.now());
                pollRunRepository.save(run);
            }
        }
        log.info("Finished Bus Arrival polling loop.");
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
