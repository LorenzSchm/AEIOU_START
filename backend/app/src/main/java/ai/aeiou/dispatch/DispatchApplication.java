package ai.aeiou.dispatch;

import ai.aeiou.freight.Catalog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DispatchApplication {
  public static void main(String[] args) {
    SpringApplication.run(DispatchApplication.class, args);
    MessageLanguage.warmUp();
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService voiceExecutor() {
    return new ThreadPoolExecutor(3, 3, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
        Thread.ofPlatform().daemon().name("voice-transcription-", 0).factory());
  }

  @Bean
  Catalog catalog() {
    return new Catalog(
        file("tariffs-2026-11.csv"), file("carrier-list.csv"), file("pickup_zones.csv"));
  }

  private Reader file(String name) {
    return new InputStreamReader(
        java.util.Objects.requireNonNull(getClass().getResourceAsStream("/kit/" + name)),
        StandardCharsets.UTF_8);
  }
}
