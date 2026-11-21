package ai.aeiou.forwarder;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class Forwarder {
  public static void main(String[] args) {
    SpringApplication.run(Forwarder.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
