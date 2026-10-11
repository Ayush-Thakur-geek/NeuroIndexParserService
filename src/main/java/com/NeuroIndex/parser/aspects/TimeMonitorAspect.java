package com.NeuroIndex.parser.aspects;

import com.NeuroIndex.parser.annotations.TimeMonitor;
import lombok.extern.log4j.Log4j2;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

// Import your project's TimeMonitor annotation.

@Aspect
@Component
@Log4j2
public class TimeMonitorAspect {

    @Around(
            value = "@annotation(timeMonitor)",
            argNames = "joinPoint,timeMonitor"
    )
    public Object monitor(
            ProceedingJoinPoint joinPoint,
            TimeMonitor timeMonitor
    ) throws Throwable {

        long started = System.nanoTime();

        try {
            return joinPoint.proceed();
        } finally {
            long elapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - started
                    );

            String logText = joinPoint.getSignature().toShortString() + " took " + elapsedMillis + " ms \n";

            Files.writeString(
                    Paths.get("Time_Monitoring.txt"),
                    logText + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );

            log.info(
                    "{} took {} ms",
                    joinPoint.getSignature().toShortString(),
                    elapsedMillis
            );
        }
    }
}
