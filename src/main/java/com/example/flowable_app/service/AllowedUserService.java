package com.example.flowable_app.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // 👈 Added
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.springframework.stereotype.Service;
import java.util.Optional;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

@Service
@RequiredArgsConstructor
@Slf4j // 👈 Added
public class AllowedUserService {
    private final DSLContext dsl;

    public Optional<String> getUserIdByEmail(String email) {
        log.info("🔍 Database Lookup: Searching for USER_ID mapping for email: [{}]", email); // 👈 Added

        Record1<Object> result = dsl.select(field("USER_ID"))
                .from(table("TBL_USER_EMAIL_MAPPING"))
                .where(field("EMAIL_ID").eq(email))
                .fetchOne();

        if (result == null) {
            log.warn("❌ Database Lookup: No mapping found for email: [{}]", email); // 👈 Added
            return Optional.empty();
        }

        String mappedId = result.value1().toString();
        log.info("✅ Database Lookup: Found mapping. Email [{}] -> USER_ID [{}]", email, mappedId); // 👈 Added
        return Optional.of(mappedId);
    }
}