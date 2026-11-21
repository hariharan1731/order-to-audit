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

    static final String FACTORY_STATE_KEY = "factory_state_code";

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

    /** The factory's 2-digit GST state code (Tamil Nadu = 33). */
    @Transactional(readOnly = true)
    public String factoryStateCode() {
        AppSetting setting = settings.findById(FACTORY_STATE_KEY).orElseThrow(
                () -> new IllegalStateException("Setting " + FACTORY_STATE_KEY + " is missing"));
        if (setting.getValue() == null || !setting.getValue().trim().matches("\\d{2}")) {
            throw new IllegalStateException("Setting " + FACTORY_STATE_KEY + " must be a 2-digit state code");
        }
        return setting.getValue().trim();
    }
}
