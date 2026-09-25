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

/** 空库启动时初始化唯一管理员账号；不写演示业务数据。 */
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
            // 首次部署默认密码，登录后请立刻在「个人中心」修改
            admin.setPasswordHash(encoder.encode("123456"));
            admin.setRole("admin");
            admin.setDisplayName("管理员");
            admin.setCreatedAt(Instant.now());
            userRepo.save(admin);

            log.info("Seeded admin user (username=admin). Change the default password after first login.");
        };
    }
}
