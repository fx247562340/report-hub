package com.reporthub.repo;

import com.reporthub.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepo extends JpaRepository<UserAccount, String> {
    UserAccount findByUsername(String username);
}
