package com.reporthub.config;

import com.reporthub.domain.UserAccount;
import com.reporthub.repo.UserAccountRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

/** Only bootstrap default admin/member accounts. No demo business data. */
@Configuration
public class SeedConfig {
    private static final Logger log = LoggerFactory.getLogger(SeedConfig.class);

    @Bean
    CommandLineRunner seedUsers(ReportHubProperties props,
                                UserAccountRepo userRepo,
                                PasswordEncoder encoder) {
        return args -> {
            if (!props.isSeed()) return;
            if (userRepo.findByUsername("admin") != null) return;

            UserAccount admin = new UserAccount();
            admin.setId("u-admin");
            admin.setUsername("admin");
            admin.setPasswordHash(encoder.encode("admin123"));
            admin.setRole("admin");
            admin.setDisplayName("管理员");
            admin.setCreatedAt(Instant.now());
            userRepo.save(admin);

            UserAccount member = new UserAccount();
            member.setId("u-member");
            member.setUsername("member");
            member.setPasswordHash(encoder.encode("member123"));
            member.setRole("member");
            member.setDisplayName("查询员");
            member.setCreatedAt(Instant.now());
            userRepo.save(member);

            log.info("Seeded default users admin/admin123 and member/member123 (config only, no demo datasets)");
        };
    }
}
