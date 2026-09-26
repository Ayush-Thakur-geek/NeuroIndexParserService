package com.NeuroIndex.parser.listners;

import com.NeuroIndex.parser.eventRecords.GraphFormationInitiationEvent;
import com.NeuroIndex.parser.service.GraphFormationService;
import com.NeuroIndex.parser.service.impl.ExportFileIngestionServiceImpl;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Log4j2
public class GraphFormationInitiationListener {

    private final GraphFormationService graphFormationService;

    public GraphFormationInitiationListener(
            GraphFormationService graphFormationService
    ) {
        this.graphFormationService = graphFormationService;
    }

    @Async("graphFormationExecutor")
    @EventListener
    public void handleGraphFormationInitiationEvent(
            GraphFormationInitiationEvent event
    ) {
        try {
            log.info(
                    "Starting graph formation for user={}",
                    event.userId()
            );

            graphFormationService.initiateGraphFormation(
                    event.userId()
            );

            log.info(
                    "Completed graph formation for user={}",
                    event.userId()
            );
        } catch (Exception exception) {
            log.error(
                    "Graph formation failed for user={}",
                    event.userId(),
                    exception
            );
        }
    }
}