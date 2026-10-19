package com.cleanprojectprogram;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

@SpringBootApplication
public class CleanProjectProgramApplication {
    public static void main(String[] args) {
        boolean cliMode = Arrays.stream(args).anyMatch(argument ->
                argument.equals("--cli") || argument.startsWith("--project-path=") || argument.equals("--project-path"));
        SpringApplication application = new SpringApplication(CleanProjectProgramApplication.class);
        if (cliMode) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (cliMode) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
