package com.orderaudit.service;

import com.orderaudit.domain.AppSetting;
import com.orderaudit.repository.AppSettingRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads configurable settings such as the default variance tolerance. */
@Service
public class SettingService {

    static final String TOLERANCE_KEY = "variance_tolerance_pct";

    private final AppSettingRepository settings;

    public SettingService(AppSettingRepository settings) {
        this.settings = settings;
    }

    /** Default tolerance in percent (the problem statement's example is 3). */
    @Transactional(readOnly = true)
    public BigDecimal defaultTolerancePct() {
        AppSetting setting = settings.findById(TOLERANCE_KEY).orElseThrow(
                () -> new IllegalStateException("Setting " + TOLERANCE_KEY + " is missing"));
        if (setting.getValue() == null || setting.getValue().isBlank()) {
            throw new IllegalStateException("Setting " + TOLERANCE_KEY + " has no value");
        }
        return new BigDecimal(setting.getValue().trim());
    }
}
