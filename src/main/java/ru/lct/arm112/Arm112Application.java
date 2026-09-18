package ru.lct.arm112;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class Arm112Application {
    public static void main(String[] args) {
        SpringApplication.run(Arm112Application.class, args);
    }
}
