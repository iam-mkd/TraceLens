package io.tracelens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import io.tracelens.runner.ExperimentRunner;
import java.util.Arrays;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TraceLensApplication {
    public static void main(String[] args) {
        if (args.length > 0 && "experiment".equals(args[0])) {
            System.exit(new ExperimentRunner().run(Arrays.copyOfRange(args, 1, args.length)));
        }
        SpringApplication.run(TraceLensApplication.class, args);
    }
}
